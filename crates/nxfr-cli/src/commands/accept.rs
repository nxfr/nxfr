use crate::ipc_client::IpcClient;
use serde_json::json;

pub async fn handle(transfer_id: String) -> anyhow::Result<()> {
    let mut client = IpcClient::connect().await?;
    let req = json!({
        "cmd": "transfer_confirm",
        "transfer_id": transfer_id,
        "accepted": true,
    });
    let resp = client.send_request(&req).await?;
    println!("{}", serde_json::to_string_pretty(&resp)?);
    Ok(())
}
