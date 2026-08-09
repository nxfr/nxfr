use crate::privacy::compute_advertised_id_now;
use log::{info, warn};
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use std::collections::HashMap;

pub const NXFR_SERVICE_TYPE: &str = "_nxfr._tcp.local.";
pub const NXFR_DEFAULT_PORT: u16 = 17394;

#[derive(thiserror::Error, Debug)]
pub enum DiscoveryError {
    #[error("mDNS error: {0}")]
    MdnsError(#[from] mdns_sd::Error),
}

#[derive(Debug, Clone)]
pub struct DiscoveredPeer {
    pub name: String,
    pub device_id_hint: String,
    pub addresses: Vec<std::net::IpAddr>,
    pub port: u16,
    pub platform: String,
    pub version: String,
}

pub struct DiscoveryManager {
    device_id: [u8; 32],
    device_name: String,
    platform: String,
    port: u16,
    daemon: ServiceDaemon,
    registered_fullname: Option<String>,
    /// Persistent browse receiver — created once, drained via try_recv().
    browse_receiver: Option<mdns_sd::Receiver<ServiceEvent>>,
    /// If the mDNS backend channel died, this holds the reason (logged once).
    degraded: Option<String>,
}

impl DiscoveryManager {
    pub fn new(
        device_id: [u8; 32],
        device_name: String,
        platform: String,
        port: u16,
    ) -> Result<Self, DiscoveryError> {
        let daemon = ServiceDaemon::new()?;

        // Create a single persistent browse receiver.
        let browse_receiver = match daemon.browse(NXFR_SERVICE_TYPE) {
            Ok(r) => Some(r),
            Err(e) => {
                warn!("mDNS browse init failed: {e}");
                None
            }
        };

        Ok(Self {
            device_id,
            device_name,
            platform,
            port,
            daemon,
            registered_fullname: None,
            browse_receiver,
            degraded: None,
        })
    }

    pub fn start_advertising(&mut self) -> Result<(), DiscoveryError> {
        // Guard: skip if already registered.
        if self.registered_fullname.is_some() {
            info!("Already advertising, skipping start_advertising");
            return Ok(());
        }

        let host_name = "nxfr-host.local.";

        let mut properties = HashMap::new();
        properties.insert("v".to_string(), "0.1".to_string());
        properties.insert("id".to_string(), compute_advertised_id_now(&self.device_id));
        properties.insert("name".to_string(), self.device_name.clone());
        properties.insert("plat".to_string(), self.platform.clone());

        let service_info = ServiceInfo::new(
            NXFR_SERVICE_TYPE,
            &self.device_name,
            host_name,
            "",
            self.port,
            Some(properties),
        )?;

        let fullname = service_info.get_fullname().to_string();
        self.daemon.register(service_info)?;
        self.registered_fullname = Some(fullname);

        Ok(())
    }

    pub fn stop_advertising(&mut self) -> Result<(), DiscoveryError> {
        if let Some(fullname) = self.registered_fullname.take() {
            // BUG 3 FIX: swallow unregister errors. mdns-sd may have already
            // cleaned up the registration internally; the important thing is
            // that registered_fullname is cleared so we know we're not advertising.
            if let Err(e) = self.daemon.unregister(&fullname) {
                warn!("mDNS unregister (non-fatal): {e}");
            }
        }
        Ok(())
    }

    pub fn browse(&self) -> Result<mdns_sd::Receiver<ServiceEvent>, DiscoveryError> {
        self.daemon.browse(NXFR_SERVICE_TYPE).map_err(Into::into)
    }

    /// Drain the persistent browse receiver for discovered peers (non-blocking).
    /// If the mDNS backend channel is dead, returns empty and sets degraded state.
    pub fn browse_snapshot(&mut self) -> Vec<DiscoveredPeer> {
        if self.degraded.is_some() {
            return Vec::new();
        }

        let receiver = match self.browse_receiver.as_ref() {
            Some(r) => r,
            None => return Vec::new(),
        };

        let mut peers = Vec::new();
        loop {
            match receiver.try_recv() {
                Ok(event) => {
                    if let ServiceEvent::ServiceResolved(info) = event {
                        let id_hint = info
                            .get_properties()
                            .get("id")
                            .map(|v| v.val_str().to_string())
                            .unwrap_or_default();
                        let name = info
                            .get_properties()
                            .get("name")
                            .map(|v| v.val_str().to_string())
                            .unwrap_or_else(|| info.get_fullname().to_string());
                        let platform = info
                            .get_properties()
                            .get("plat")
                            .map(|v| v.val_str().to_string())
                            .unwrap_or_default();
                        let version = info
                            .get_properties()
                            .get("v")
                            .map(|v| v.val_str().to_string())
                            .unwrap_or_default();
                        let addresses: Vec<std::net::IpAddr> =
                            info.get_addresses().iter().copied().collect();
                        let port = info.get_port();

                        peers.push(DiscoveredPeer {
                            name,
                            device_id_hint: id_hint,
                            addresses,
                            port,
                            platform,
                            version,
                        });
                    }
                }
                Err(e) => {
                    let err_str = format!("{e:?}");
                    if err_str.contains("Disconnected") {
                        let reason = "mDNS backend channel closed".to_string();
                        warn!("mDNS browse degraded: {reason}");
                        self.degraded = Some(reason);
                    }
                    // Empty or Disconnected — stop draining.
                    break;
                }
            }
        }
        peers
    }

    /// Returns the degraded reason if the mDNS backend died.
    pub fn is_degraded(&self) -> Option<&str> {
        self.degraded.as_deref()
    }

    pub fn shutdown(self) -> Result<(), DiscoveryError> {
        self.daemon.shutdown().map(|_| ()).map_err(Into::into)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_manager() {
        let device_id = [0u8; 32];
        let manager = DiscoveryManager::new(device_id, "TestDevice".into(), "linux".into(), 12345);
        assert!(manager.is_ok());
    }

    #[test]
    fn test_start_stop_advertising() {
        let device_id = [0u8; 32];
        let mut manager =
            DiscoveryManager::new(device_id, "TestDevice".into(), "linux".into(), 12345).unwrap();

        assert!(manager.start_advertising().is_ok());
        assert!(manager.registered_fullname.is_some());

        assert!(manager.stop_advertising().is_ok());
        assert!(manager.registered_fullname.is_none());

        manager.shutdown().unwrap();
    }

    #[test]
    fn test_browse_snapshot_no_panic() {
        let device_id = [0u8; 32];
        let mut manager =
            DiscoveryManager::new(device_id, "TestDevice".into(), "linux".into(), 12345).unwrap();
        // Should return empty without panic, multiple times.
        let _ = manager.browse_snapshot();
        let _ = manager.browse_snapshot();
        let _ = manager.browse_snapshot();
        assert!(manager.is_degraded().is_none());
        manager.shutdown().unwrap();
    }
}
