use crate::ipc_client::IpcClient;
use serde_json::json;

pub async fn handle() -> anyhow::Result<()> {
    let mut client = IpcClient::connect().await?;
    let req = json!({ "cmd": "status" });
    let resp = client.send_request(&req).await?;

    // Check for daemon error response.
    if resp["ok"].as_bool() != Some(true) {
        let error = resp["error"].as_str().unwrap_or("daemon returned an error");
        anyhow::bail!("Status failed: {error}");
    }

    let state = resp["state"].as_str().unwrap_or("unknown");
    let device_id = resp["device_id"].as_str().unwrap_or("unknown");
    let receiving = resp["receiving_enabled"].as_bool().unwrap_or(false);
    let receiving_str = if receiving { "enabled" } else { "disabled" };
    let discovery = resp["discovery"].as_str().unwrap_or("unknown");
    let paired = resp["paired_devices"].as_u64().unwrap_or(0);
    let transfers = resp["active_transfers"]
        .as_array()
        .map(|a| a.len())
        .unwrap_or(0);
    let transfers_str = if transfers == 0 {
        "none active".to_string()
    } else {
        format!("{} active", transfers)
    };
    let pending = resp["pending_offers"].as_u64().unwrap_or(0);

    println!("NXFR Daemon Status");
    println!("  State:      {}", state);
    println!("  Device ID:  {}", device_id);
    println!("  Receiving:  {}", receiving_str);
    println!("  Discovery:  {}", discovery);
    println!("  Paired:     {} devices", paired);
    println!("  Transfers:  {}", transfers_str);
    if pending > 0 {
        println!("  Pending:    {} offers", pending);
    }

    Ok(())
}
