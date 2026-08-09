pub mod manager;
pub mod privacy;

pub use manager::{
    DiscoveredPeer, DiscoveryError, DiscoveryManager, NXFR_DEFAULT_PORT, NXFR_SERVICE_TYPE,
};
pub use privacy::{compute_advertised_id, compute_advertised_id_now};
