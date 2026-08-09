use crate::ipc_client::IpcClient;
use serde_json::json;

pub async fn handle(device: String) -> anyhow::Result<()> {
    let mut client = IpcClient::connect().await?;
    let req = json!({
        "cmd": "unpair",
        "device_id": device,
    });
    let resp = client.send_request(&req).await?;
    println!("{}", serde_json::to_string_pretty(&resp)?);
    Ok(())
}
