/// Quick mDNS self-test: register and immediately browse for _nxfr._tcp
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use std::collections::HashMap;

fn main() {
    let mdns = ServiceDaemon::new().expect("daemon");

    // Register
    let mut props = HashMap::new();
    props.insert("v".to_string(), "0.1".to_string());
    props.insert("id".to_string(), "abcdef0123456789".to_string());
    props.insert("name".to_string(), "TestDevice".to_string());
    props.insert("plat".to_string(), "linux".to_string());

    let info = ServiceInfo::new(
        "_nxfr._tcp.local.",
        "TestDevice",
        "nxfr-test.local.",
        "",
        17394,
        props,
    )
    .expect("service info");

    mdns.register(info).expect("register");
    println!("Registered _nxfr._tcp");

    // Browse
    let receiver = mdns.browse("_nxfr._tcp.local.").expect("browse");
    println!("Browsing for 5 seconds...");

    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while std::time::Instant::now() < deadline {
        match receiver.recv_timeout(std::time::Duration::from_millis(500)) {
            Ok(ServiceEvent::ServiceResolved(info)) => {
                println!(
                    "FOUND: {} @ {:?}:{}",
                    info.get_fullname(),
                    info.get_addresses(),
                    info.get_port()
                );
                for prop in info.get_properties().iter() {
                    println!("  TXT: {}={}", prop.key(), prop.val_str());
                }
            }
            Ok(event) => {
                println!("Event: {:?}", event);
            }
            Err(_) => {} // timeout, continue
        }
    }

    mdns.shutdown().ok();
}
