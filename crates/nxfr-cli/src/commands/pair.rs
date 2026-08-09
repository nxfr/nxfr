use crate::ipc_client::IpcClient;
use dialoguer::Confirm;
use serde_json::json;

pub async fn handle(device: String, addr: Option<String>) -> anyhow::Result<()> {
    let mut client = IpcClient::connect().await?;

    let req = json!({
        "cmd": "pair",
        "device": device,
        "addr": addr,
    });
    client.send_followup(&req).await?;

    while let Some(event) = client.read_event().await? {
        match event["type"].as_str() {
            Some("sas_prompt") => {
                let code = event["code"].as_str().unwrap_or("UNKNOWN");
                println!("SAS Code: {}", code);

                let accepted = Confirm::new()
                    .with_prompt("Does the code match?")
                    .interact()?;

                let confirm_req = json!({
                    "cmd": "pair_confirm",
                    "accepted": accepted,
                });
                client.send_followup(&confirm_req).await?;
            }
            Some("pair_success") => {
                println!("Pairing successful!");
                break;
            }
            Some("pair_failed") => {
                let err_msg = event["message"].as_str().unwrap_or("Unknown error");
                eprintln!("Pairing failed: {}", err_msg);
                break;
            }
            Some("error") => {
                let err_msg = event["message"].as_str().unwrap_or("Unknown error");
                eprintln!("Error: {}", err_msg);
                break;
            }
            _ => {}
        }
    }

    Ok(())
}
