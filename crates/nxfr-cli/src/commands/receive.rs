use crate::ipc_client::IpcClient;
use serde_json::json;

pub async fn handle(enabled: bool) -> anyhow::Result<()> {
    let mut client = IpcClient::connect().await?;
    let req = json!({ "cmd": "set_receiving", "enabled": enabled });
    let resp = client.send_request(&req).await?;

    if resp["ok"].as_bool() == Some(true) {
        let state = if enabled { "enabled" } else { "disabled" };
        println!("Receiving {state}");
    } else {
        let error = resp["error"]
            .as_str()
            .unwrap_or("daemon returned an error with no message");
        anyhow::bail!("Failed to toggle receiving: {error}");
    }

    Ok(())
}
