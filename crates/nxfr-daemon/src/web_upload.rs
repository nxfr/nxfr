use crate::identity::PersistentIdentity;
use nxfr_storage::config::NxfrConfig;
use rustls::ServerConfig;
use std::collections::HashMap;
use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;
use tokio::task::JoinHandle;
use tokio_rustls::TlsAcceptor;

const WEB_UPLOAD_PORT: u16 = 17396;
const MAX_ATTEMPTS: u32 = 5;
const RATE_LIMIT_WINDOW: Duration = Duration::from_secs(60);

const HTML_PAGE: &str = r#"<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>NXFR — Upload File</title>
<style>
body{font-family:system-ui;background:#0F172A;color:#E2E8F0;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}
.card{background:#1E293B;border-radius:16px;padding:32px;max-width:420px;width:90%;box-shadow:0 4px 24px #00000066}
h1{color:#00E5FF;font-size:24px;margin:0 0 8px}
.sub{color:#94A3B8;margin:0 0 24px;font-size:14px}
input[type=file]{display:none}
.drop{border:2px dashed #334155;border-radius:12px;padding:48px 24px;text-align:center;cursor:pointer;transition:border-color .2s}
.drop:hover,.drop.over{border-color:#00E5FF}
.btn{background:#00E5FF;color:#0F172A;border:none;border-radius:8px;padding:12px 24px;font-weight:700;cursor:pointer;width:100%;margin-top:16px;font-size:16px}
.btn:disabled{opacity:.5;cursor:not-allowed}
.progress{width:100%;height:8px;background:#334155;border-radius:4px;margin-top:12px;overflow:hidden}
.bar{height:100%;background:#00E5FF;width:0%;transition:width .3s}
.status{text-align:center;margin-top:8px;font-size:14px;color:#94A3B8}
</style></head><body>
<div class="card">
<h1>NXFR</h1>
<p class="sub">Upload a file to this device</p>
<div class="drop" id="drop">Click or drag a file here</div>
<input type="file" id="file">
<div class="progress" style="display:none" id="pg"><div class="bar" id="bar"></div></div>
<p class="status" id="st"></p>
<button class="btn" id="btn" disabled>Upload</button>
</div>
<script>
const t=location.hash.slice(3);
const drop=document.getElementById('drop'),fi=document.getElementById('file'),
btn=document.getElementById('btn'),pg=document.getElementById('pg'),
bar=document.getElementById('bar'),st=document.getElementById('st');
let sel=null;
drop.onclick=()=>fi.click();
fi.onchange=e=>{sel=e.target.files[0];drop.textContent=sel.name;btn.disabled=false;};
['dragenter','dragover'].forEach(e=>drop.addEventListener(e,ev=>{ev.preventDefault();drop.classList.add('over');}));
['dragleave','drop'].forEach(e=>drop.addEventListener(e,ev=>{ev.preventDefault();drop.classList.remove('over');}));
drop.addEventListener('drop',ev=>{sel=ev.dataTransfer.files[0];drop.textContent=sel.name;btn.disabled=false;});
btn.onclick=()=>{
if(!sel)return;
const fd=new FormData();fd.append('file',sel);
const xhr=new XMLHttpRequest();
xhr.open('POST','/upload');
xhr.setRequestHeader('Authorization','Bearer '+t);
xhr.upload.onprogress=e=>{if(e.lengthComputable){const p=Math.round(e.loaded/e.total*100);bar.style.width=p+'%';st.textContent=p+'%';}};
pg.style.display='block';btn.disabled=true;
xhr.onload=()=>{if(xhr.status===200){st.textContent='Upload complete \u2713';bar.style.width='100%';bar.style.background='#22C55E';}else{st.textContent='Error: '+xhr.responseText;bar.style.background='#EF4444';btn.disabled=false;}};
xhr.onerror=()=>{st.textContent='Network error';btn.disabled=false;};
xhr.send(fd);
};
</script></body></html>"#;

fn build_web_tls_config(identity: &PersistentIdentity) -> Result<Arc<ServerConfig>, Box<dyn std::error::Error + Send + Sync>> {
    let cert_der = identity.certificate();
    let key_der = identity.private_key();
    let config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert_der], key_der)?;
    Ok(Arc::new(config))
}

fn generate_token() -> String {
    let mut bytes = [0u8; 16];
    getrandom::getrandom(&mut bytes).expect("getrandom failed");
    hex::encode(bytes)
}

fn sanitize_filename(name: &str) -> String {
    name.chars().map(|c| if c.is_alphanumeric() || c == '.' || c == '-' || c == '_' { c } else { '_' }).collect()
}

pub struct WebUploadServer {
    token: String,
    receive_dir: PathBuf,
    max_file_size: u64,
    rate_limiter: Arc<Mutex<HashMap<IpAddr, (u32, Instant)>>>,
}

impl WebUploadServer {
    pub fn new(receive_dir: PathBuf, max_file_size: u64) -> Self {
        Self {
            token: generate_token(),
            receive_dir,
            max_file_size,
            rate_limiter: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub async fn start(identity: &PersistentIdentity, config: &NxfrConfig) -> Result<(JoinHandle<()>, String, u16), Box<dyn std::error::Error + Send + Sync>> {
        let receive_dir = config.receive_dir.clone();
        let server = Self::new(receive_dir, 1024 * 1024 * 1024); // 1 GB default
        let token = server.token.clone();
        let port = WEB_UPLOAD_PORT;

        let tls_config = build_web_tls_config(identity)?;
        let acceptor = TlsAcceptor::from(tls_config);
        
        let listener = TcpListener::bind(("0.0.0.0", port)).await?;

        let server_arc = Arc::new(server);

        let handle = tokio::spawn(async move {
            loop {
                if let Ok((stream, addr)) = listener.accept().await {
                    let acceptor = acceptor.clone();
                    let server_arc = server_arc.clone();
                    tokio::spawn(async move {
                        if let Ok(mut tls_stream) = acceptor.accept(stream).await {
                            if let Err(e) = server_arc.handle_connection(&mut tls_stream, addr.ip()).await {
                                log::error!("Web upload error: {}", e);
                            }
                        }
                    });
                }
            }
        });

        Ok((handle, token, port))
    }

    async fn handle_connection(&self, stream: &mut tokio_rustls::server::TlsStream<TcpStream>, ip: IpAddr) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let mut headers_buf = Vec::new();
        let mut buf = [0u8; 1024];
        let mut body_start = 0;

        loop {
            let n = stream.read(&mut buf).await?;
            if n == 0 {
                return Ok(());
            }
            headers_buf.extend_from_slice(&buf[..n]);

            if let Some(pos) = headers_buf.windows(4).position(|window| window == b"\r\n\r\n") {
                body_start = pos + 4;
                break;
            }
            if headers_buf.len() > 8192 {
                return Ok(()); // headers too large
            }
        }

        let headers_str = String::from_utf8_lossy(&headers_buf[..body_start]);
        let mut lines = headers_str.lines();
        let request_line = lines.next().unwrap_or("");
        
        let mut parts = request_line.split_whitespace();
        let method = parts.next().unwrap_or("");
        let path = parts.next().unwrap_or("");

        if method == "GET" && path == "/" {
            let response = format!(
                "HTTP/1.1 200 OK\r\n\
                 Content-Type: text/html\r\n\
                 Content-Length: {}\r\n\
                 Connection: close\r\n\
                 \r\n\
                 {}",
                HTML_PAGE.len(),
                HTML_PAGE
            );
            stream.write_all(response.as_bytes()).await?;
            return Ok(());
        }

        if method == "POST" && path == "/upload" {
            // Check rate limit
            let mut rate_limited = false;
            {
                let mut rl = self.rate_limiter.lock().await;
                let now = Instant::now();
                let entry = rl.entry(ip).or_insert((0, now));
                if now.duration_since(entry.1) > RATE_LIMIT_WINDOW {
                    entry.0 = 1;
                    entry.1 = now;
                } else {
                    entry.0 += 1;
                    if entry.0 > MAX_ATTEMPTS {
                        rate_limited = true;
                    }
                }
            }

            if rate_limited {
                let response = "HTTP/1.1 429 Too Many Requests\r\n\
                                Content-Type: application/json\r\n\
                                Connection: close\r\n\
                                \r\n\
                                {\"error\": \"Rate limit exceeded\"}";
                stream.write_all(response.as_bytes()).await?;
                return Ok(());
            }

            let mut content_length: Option<usize> = None;
            let mut boundary: Option<String> = None;
            let mut auth_token: Option<String> = None;

            for line in lines {
                if line.to_lowercase().starts_with("content-length:") {
                    content_length = line[15..].trim().parse().ok();
                } else if line.to_lowercase().starts_with("content-type: multipart/form-data") {
                    if let Some(pos) = line.find("boundary=") {
                        boundary = Some(line[pos + 9..].trim().to_string());
                    }
                } else if line.to_lowercase().starts_with("authorization: bearer ") {
                    auth_token = Some(line[22..].trim().to_string());
                }
            }

            if auth_token.as_deref() != Some(&self.token) {
                let response = "HTTP/1.1 403 Forbidden\r\n\
                                Content-Type: application/json\r\n\
                                Connection: close\r\n\
                                \r\n\
                                {\"error\": \"Invalid token\"}";
                stream.write_all(response.as_bytes()).await?;
                return Ok(());
            }

            if let (Some(mut length), Some(boundary_str)) = (content_length, boundary) {
                if length as u64 > self.max_file_size {
                    let response = "HTTP/1.1 413 Payload Too Large\r\n\
                                    Connection: close\r\n\
                                    \r\n";
                    stream.write_all(response.as_bytes()).await?;
                    return Ok(());
                }

                let boundary_bytes = format!("--{}", boundary_str).into_bytes();
                let end_boundary_bytes = format!("--{}--", boundary_str).into_bytes();

                let inbox_dir = self.receive_dir.join("web-inbox");
                std::fs::create_dir_all(&inbox_dir)?;
                
                let mut rand_bytes = [0u8; 8];
                getrandom::getrandom(&mut rand_bytes).expect("getrandom failed");
                let filename = format!("web_upload_{}.tmp", hex::encode(rand_bytes));
                let final_path = inbox_dir.join(sanitize_filename(&filename));
                
                let _canonical_inbox = std::fs::canonicalize(&inbox_dir)?;
                let mut file = tokio::fs::File::create(&final_path).await?;
                
                let mut buffer = headers_buf.split_off(body_start);
                let mut file_started = false;

                loop {
                    if !file_started {
                        if let Some(pos) = buffer.windows(4).position(|w| w == b"\r\n\r\n") {
                            buffer.drain(..pos + 4);
                            file_started = true;
                        } else {
                            if length == 0 { break; }
                            let mut read_buf = vec![0; std::cmp::min(8192, length)];
                            let n = stream.read(&mut read_buf).await?;
                            if n == 0 { break; }
                            length -= n;
                            buffer.extend_from_slice(&read_buf[..n]);
                            continue;
                        }
                    }

                    if file_started {
                        if let Some(pos) = buffer.windows(boundary_bytes.len()).position(|w| w == &boundary_bytes[..]) {
                            let write_len = if pos >= 2 { pos - 2 } else { 0 };
                            file.write_all(&buffer[..write_len]).await?;
                            break;
                        } else if buffer.len() > boundary_bytes.len() * 2 {
                            let safe_len = buffer.len() - boundary_bytes.len() * 2;
                            file.write_all(&buffer[..safe_len]).await?;
                            buffer.drain(..safe_len);
                        }

                        if length == 0 {
                            break;
                        }

                        let mut read_buf = vec![0; std::cmp::min(8192, length)];
                        let n = stream.read(&mut read_buf).await?;
                        if n == 0 { break; }
                        length -= n;
                        buffer.extend_from_slice(&read_buf[..n]);
                    }
                }

                let response = "HTTP/1.1 200 OK\r\n\
                                Content-Type: text/plain\r\n\
                                Connection: close\r\n\
                                \r\n\
                                Upload successful";
                stream.write_all(response.as_bytes()).await?;
                return Ok(());
            }
        }

        let response = "HTTP/1.1 404 Not Found\r\n\
                        Connection: close\r\n\
                        \r\n";
        stream.write_all(response.as_bytes()).await?;
        
        Ok(())
    }
}
