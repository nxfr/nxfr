use futures_util::FutureExt;
use rustls::ServerConfig;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{Mutex, RwLock};
use tokio::task::JoinHandle;
use tokio_rustls::TlsAcceptor;
use tokio_util::sync::CancellationToken;

pub const DEFAULT_WEB_PORT: u16 = 17396;
pub const MAX_UPLOAD_LIMIT: u64 = 10 * 1024 * 1024 * 1024; // 10 GB
const MAX_PORT_ATTEMPTS: u16 = 10;
const MAX_FAILED_ATTEMPTS: u32 = 5;
const RATE_LIMIT_WINDOW: Duration = Duration::from_secs(60);
const BLOCK_DURATION: Duration = Duration::from_secs(300);
const DEFAULT_EXPIRY_DURATION: Duration = Duration::from_secs(600); // 10 minutes

pub fn get_default_expiry_duration() -> Duration {
    if let Ok(secs_str) = std::env::var("NXFR_WEB_EXPIRY_SECS") {
        if let Ok(secs) = secs_str.parse::<u64>() {
            return Duration::from_secs(secs);
        }
    }
    DEFAULT_EXPIRY_DURATION
}

/// RAII guard to track in-flight transfers and defer server inactivity shutdown.
pub struct ActiveTransferGuard {
    counter: Arc<std::sync::atomic::AtomicUsize>,
}

impl ActiveTransferGuard {
    pub fn new(counter: Arc<std::sync::atomic::AtomicUsize>) -> Self {
        counter.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        Self { counter }
    }
}

impl Drop for ActiveTransferGuard {
    fn drop(&mut self) {
        self.counter
            .fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
    }
}

/// RAII guard to ensure uncommitted temporary upload files are cleaned up on error, abort, or disconnect.
struct TmpFileGuard {
    path: PathBuf,
    committed: bool,
}

impl TmpFileGuard {
    fn new(path: PathBuf) -> Self {
        Self {
            path,
            committed: false,
        }
    }

    fn commit(&mut self) {
        self.committed = true;
    }
}

impl Drop for TmpFileGuard {
    fn drop(&mut self) {
        if !self.committed {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

/// Parses "bytes=start-end" or "bytes=start-" from a Range header.
/// Returns inclusive (start, end) byte offsets, or None if malformed.
fn parse_range_header(header_val: &str, file_size: u64) -> Option<(u64, u64)> {
    let s = header_val.trim();
    if !s.starts_with("bytes=") {
        return None;
    }
    let range_spec = &s[6..];
    let range_part = range_spec.split(',').next()?.trim();
    let (start_str, end_str) = range_part.split_once('-')?;

    if start_str.is_empty() {
        // Suffix range: "bytes=-500" means last 500 bytes
        let suffix_len: u64 = end_str.parse().ok()?;
        if suffix_len == 0 || suffix_len > file_size {
            return None;
        }
        Some((file_size - suffix_len, file_size - 1))
    } else {
        let start: u64 = start_str.parse().ok()?;
        let end = if end_str.is_empty() {
            file_size - 1
        } else {
            end_str.parse::<u64>().ok()?
        };
        if start > end || start >= file_size {
            return None;
        }
        Some((start, end.min(file_size - 1)))
    }
}

/// Writes a ZIP local file header with data descriptor flag (bit 3).
/// CRC32 and sizes are set to 0; actual values go in the data descriptor after file data.
async fn write_zip_local_header(
    stream: &mut (impl AsyncWriteExt + Unpin),
    filename: &[u8],
    _file_size: u64,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut header = Vec::with_capacity(30 + filename.len());
    // Local file header signature
    header.extend_from_slice(&0x04034b50u32.to_le_bytes());
    // Version needed to extract (2.0)
    header.extend_from_slice(&20u16.to_le_bytes());
    // General purpose bit flag (bit 3 = data descriptor)
    header.extend_from_slice(&0x0008u16.to_le_bytes());
    // Compression method (0 = STORE)
    header.extend_from_slice(&0u16.to_le_bytes());
    // Last mod file time (00:00:00)
    header.extend_from_slice(&0u16.to_le_bytes());
    // Last mod file date (1980-01-01)
    header.extend_from_slice(&0x0021u16.to_le_bytes());
    // CRC-32 (0, will be in data descriptor)
    header.extend_from_slice(&0u32.to_le_bytes());
    // Compressed size (0, will be in data descriptor)
    header.extend_from_slice(&0u32.to_le_bytes());
    // Uncompressed size (0, will be in data descriptor)
    header.extend_from_slice(&0u32.to_le_bytes());
    // File name length
    header.extend_from_slice(&(filename.len() as u16).to_le_bytes());
    // Extra field length
    header.extend_from_slice(&0u16.to_le_bytes());
    // File name
    header.extend_from_slice(filename);
    stream.write_all(&header).await?;
    Ok(())
}

/// Writes a ZIP data descriptor after file data (with signature).
async fn write_zip_data_descriptor(
    stream: &mut (impl AsyncWriteExt + Unpin),
    crc32: u32,
    size: u64,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut desc = Vec::with_capacity(16);
    // Data descriptor signature
    desc.extend_from_slice(&0x08074b50u32.to_le_bytes());
    // CRC-32
    desc.extend_from_slice(&crc32.to_le_bytes());
    // Compressed size (same as uncompressed for STORE)
    desc.extend_from_slice(&(size as u32).to_le_bytes());
    // Uncompressed size
    desc.extend_from_slice(&(size as u32).to_le_bytes());
    stream.write_all(&desc).await?;
    Ok(())
}

/// Writes a ZIP central directory file header.
async fn write_zip_central_entry(
    stream: &mut (impl AsyncWriteExt + Unpin),
    filename: &[u8],
    file_size: u64,
    crc32: u32,
    local_offset: u64,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut entry = Vec::with_capacity(46 + filename.len());
    // Central directory file header signature
    entry.extend_from_slice(&0x02014b50u32.to_le_bytes());
    // Version made by (2.0, Unix)
    entry.extend_from_slice(&0x0314u16.to_le_bytes());
    // Version needed to extract (2.0)
    entry.extend_from_slice(&20u16.to_le_bytes());
    // General purpose bit flag (bit 3 = data descriptor)
    entry.extend_from_slice(&0x0008u16.to_le_bytes());
    // Compression method (0 = STORE)
    entry.extend_from_slice(&0u16.to_le_bytes());
    // Last mod file time
    entry.extend_from_slice(&0u16.to_le_bytes());
    // Last mod file date (1980-01-01)
    entry.extend_from_slice(&0x0021u16.to_le_bytes());
    // CRC-32
    entry.extend_from_slice(&crc32.to_le_bytes());
    // Compressed size
    entry.extend_from_slice(&(file_size as u32).to_le_bytes());
    // Uncompressed size
    entry.extend_from_slice(&(file_size as u32).to_le_bytes());
    // File name length
    entry.extend_from_slice(&(filename.len() as u16).to_le_bytes());
    // Extra field length
    entry.extend_from_slice(&0u16.to_le_bytes());
    // File comment length
    entry.extend_from_slice(&0u16.to_le_bytes());
    // Disk number start
    entry.extend_from_slice(&0u16.to_le_bytes());
    // Internal file attributes
    entry.extend_from_slice(&0u16.to_le_bytes());
    // External file attributes
    entry.extend_from_slice(&0u32.to_le_bytes());
    // Relative offset of local header
    entry.extend_from_slice(&(local_offset as u32).to_le_bytes());
    // File name
    entry.extend_from_slice(filename);
    stream.write_all(&entry).await?;
    Ok(())
}

/// Writes the ZIP end of central directory record.
async fn write_zip_eocd(
    stream: &mut (impl AsyncWriteExt + Unpin),
    entry_count: u16,
    central_dir_size: u32,
    central_dir_offset: u32,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut eocd = Vec::with_capacity(22);
    // End of central directory signature
    eocd.extend_from_slice(&0x06054b50u32.to_le_bytes());
    // Number of this disk
    eocd.extend_from_slice(&0u16.to_le_bytes());
    // Disk where central directory starts
    eocd.extend_from_slice(&0u16.to_le_bytes());
    // Number of entries on this disk
    eocd.extend_from_slice(&entry_count.to_le_bytes());
    // Total number of entries
    eocd.extend_from_slice(&entry_count.to_le_bytes());
    // Size of central directory
    eocd.extend_from_slice(&central_dir_size.to_le_bytes());
    // Offset of start of central directory
    eocd.extend_from_slice(&central_dir_offset.to_le_bytes());
    // ZIP file comment length
    eocd.extend_from_slice(&0u16.to_le_bytes());
    stream.write_all(&eocd).await?;
    Ok(())
}


const HTML_PAGE: &str = r#"<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>NXFR — Web Upload</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;background:#0F172A;color:#E2E8F0;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;padding:16px;box-sizing:border-box}
.card{background:#1E293B;border-radius:16px;padding:32px;max-width:440px;width:100%;box-shadow:0 4px 24px #00000066}
h1{color:#00E5FF;font-size:24px;margin:0 0 8px}
.sub{color:#94A3B8;margin:0 0 20px;font-size:14px}
input[type=file]{display:none}
.drop{border:2px dashed #334155;border-radius:12px;padding:36px 20px;text-align:center;cursor:pointer;transition:border-color .2s;word-break:break-all}
.drop:hover,.drop.over{border-color:#00E5FF}
.btn{background:#00E5FF;color:#0F172A;border:none;border-radius:8px;padding:12px 24px;font-weight:700;cursor:pointer;width:100%;margin-top:16px;font-size:16px;transition:background 0.2s}
.btn:hover{background:#38BDF8}
.btn:disabled{opacity:.5;cursor:not-allowed}
.btn-cancel{background:#EF4444;color:#FFF;margin-top:8px;padding:6px 14px;font-size:12px;width:auto;display:inline-block;border-radius:6px}
.btn-cancel:hover{background:#DC2626}
.progress{width:100%;height:8px;background:#334155;border-radius:4px;margin-top:12px;overflow:hidden}
.bar{height:100%;background:#00E5FF;width:0%;transition:width .3s}
.status{text-align:center;margin-top:8px;font-size:13px;color:#94A3B8;font-family:monospace}
.current-file{font-size:13px;font-weight:600;color:#38BDF8;margin-top:12px;text-align:center;word-break:break-all}
.fp-box{font-size:12px;color:#94A3B8;background:#0F172A;padding:10px;border-radius:8px;word-break:break-all;margin-bottom:16px;border:1px solid #1E293B}
.pin-input{width:100%;box-sizing:border-box;background:#0F172A;border:2px solid #334155;border-radius:8px;color:#F8FAFC;font-size:24px;font-family:monospace;text-align:center;letter-spacing:6px;padding:12px;outline:none;transition:border-color 0.2s}
.pin-input:focus{border-color:#00E5FF}
.pin-err{color:#EF4444;font-size:13px;margin-top:10px;display:none;text-align:center;font-weight:600}
.queue-box{margin-top:16px;border-top:1px solid #334155;padding-top:12px}
.queue-header{font-size:12px;color:#94A3B8;margin-bottom:8px;font-weight:600;display:flex;justify-content:space-between;align-items:center}
.queue-list{display:flex;flex-direction:column;gap:6px;max-height:140px;overflow-y:auto}
.queue-item{display:flex;align-items:center;justify-content:space-between;background:#0F172A;padding:6px 10px;border-radius:6px;border:1px solid #334155;font-size:12px}
.queue-name{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:240px;color:#E2E8F0}
.btn-remove{background:transparent;color:#EF4444;border:none;cursor:pointer;font-size:12px;font-weight:700;padding:2px 6px;border-radius:4px}
.btn-remove:hover{background:#EF444422}
</style></head><body>

<div class="card" id="pin-card" style="display:none">
<h1>NXFR Protected Upload</h1>
<p class="sub">Enter the security PIN set by the recipient to upload files.</p>
<div class="fp-box">
Connected Device Fingerprint:<br>
<strong style="color:#00E5FF;font-family:monospace;font-size:11px;">{{FINGERPRINT}}</strong>
</div>
<div style="margin:24px 0 16px">
<input type="text" id="pin-input" class="pin-input" inputmode="numeric" pattern="[0-9]*" maxlength="8" placeholder="Enter PIN" autocomplete="off">
<div id="pin-err" class="pin-err"></div>
</div>
<button class="btn" id="btn-unlock" onclick="unlockUploadWithPin()" style="margin-top:8px">Unlock Upload</button>
</div>

<div class="card" id="main-card">
<h1>NXFR Direct Upload</h1>
<p class="sub">Select or drop files to send to this device</p>
<div class="fp-box">
Connected Device Fingerprint:<br>
<strong style="color:#00E5FF;font-family:monospace;font-size:11px;">{{FINGERPRINT}}</strong>
</div>
<div class="drop" id="drop">Click or drag files here</div>
<input type="file" id="file" multiple>
<input type="file" id="folder" webkitdirectory directory multiple style="display:none">
<div style="text-align:center;margin-top:8px"><button type="button" style="background:transparent;color:#94A3B8;border:1px solid #334155;border-radius:6px;padding:4px 12px;cursor:pointer;font-size:12px" onclick="document.getElementById('folder').click()">Select Folder</button></div>
<div id="current-file" class="current-file" style="display:none"></div>
<div class="progress" style="display:none" id="pg"><div class="bar" id="bar"></div></div>
<p class="status" id="st"></p>
<div style="text-align:center">
<button type="button" class="btn btn-cancel" id="btn-cancel" style="display:none" onclick="cancelCurrentUpload()">Cancel Upload</button>
</div>
<div id="queue-box" class="queue-box" style="display:none">
<div class="queue-header">
<span>UPCOMING QUEUE (<span id="queue-count">0</span>)</span>
<button type="button" style="background:transparent;color:#94A3B8;border:none;cursor:pointer;font-size:11px" onclick="clearQueue()">Clear All</button>
</div>
<div id="queue-list" class="queue-list"></div>
</div>
</div>
<script>
const hasPin = {{HAS_PIN}};
const hashToken = location.hash.replace(/^#t=/, '').replace(/^#/, '');
const params = new URLSearchParams(location.search);
let t = hashToken || params.get('t') || '';

if (hasPin && !t) {
  document.getElementById('pin-card').style.display = 'block';
  document.getElementById('main-card').style.display = 'none';
  const pi = document.getElementById('pin-input');
  setTimeout(() => pi.focus(), 100);
  pi.addEventListener('keydown', e => { if (e.key === 'Enter') unlockUploadWithPin(); });
} else {
  document.getElementById('pin-card').style.display = 'none';
  document.getElementById('main-card').style.display = 'block';
}

async function unlockUploadWithPin() {
  const pinInput = document.getElementById('pin-input');
  const pinVal = pinInput.value.trim();
  const pinErr = document.getElementById('pin-err');
  const btnUnlock = document.getElementById('btn-unlock');

  if (!pinVal) {
    pinErr.style.display = 'block';
    pinErr.textContent = 'Please enter the PIN';
    return;
  }

  btnUnlock.disabled = true;
  btnUnlock.textContent = 'Verifying...';
  pinErr.style.display = 'none';

  try {
    const res = await fetch('/auth', {
      headers: { 'Authorization': 'Bearer ' + pinVal }
    });
    if (res.ok) {
      t = pinVal;
      document.getElementById('pin-card').style.display = 'none';
      document.getElementById('main-card').style.display = 'block';
    } else {
      pinErr.style.display = 'block';
      pinErr.textContent = '❌ Incorrect PIN — Access Denied';
      pinInput.value = '';
      pinInput.focus();
    }
  } catch (err) {
    pinErr.style.display = 'block';
    pinErr.textContent = 'Network error \u2014 try again';
  } finally {
    btnUnlock.disabled = false;
    btnUnlock.textContent = 'Unlock Upload';
  }
}

function fmtBytes(b){
  if(!b||b<=0) return '0 B';
  const u=['B','KB','MB','GB','TB'];
  const i=Math.floor(Math.log(b)/Math.log(1024));
  return (b/Math.pow(1024,i)).toFixed(1)+' '+u[i];
}
function fmtSpeed(bps){
  if(bps<=0) return '...';
  if(bps>=1073741824) return (bps/1073741824).toFixed(1)+' GB/s';
  if(bps>=1048576) return (bps/1048576).toFixed(1)+' MB/s';
  if(bps>=1024) return (bps/1024).toFixed(0)+' KB/s';
  return bps.toFixed(0)+' B/s';
}
function fmtDuration(s){
  if(s<60) return Math.ceil(s)+'s';
  const m=Math.floor(s/60),r=Math.ceil(s%60);
  return m+':'+String(r).padStart(2,'0');
}
function escapeHtml(s){
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

const drop=document.getElementById('drop'),fi=document.getElementById('file'),
folderInput=document.getElementById('folder'),
pg=document.getElementById('pg'),bar=document.getElementById('bar'),st=document.getElementById('st'),
curFileEl=document.getElementById('current-file'),cancelBtn=document.getElementById('btn-cancel');

let uploadQueue = [];
let isUploading = false;
let currentXhr = null;
let currentFile = null;

drop.onclick=()=>fi.click();
fi.onchange=e=>{if(e.target.files&&e.target.files.length){enqueueFiles(e.target.files);fi.value='';}};
if(folderInput) folderInput.onchange=e=>{
  if(e.target.files&&e.target.files.length){
    for(let i=0;i<e.target.files.length;i++){
      const f=e.target.files[i];
      const entry={file:f,name:f.name,size:f.size,relativePath:f.webkitRelativePath||f.name};
      uploadQueue.push(entry);
    }
    renderQueue();
    if(!isUploading) processQueue();
    folderInput.value='';
  }
};
['dragenter','dragover'].forEach(e=>drop.addEventListener(e,ev=>{ev.preventDefault();drop.classList.add('over');}));
['dragleave','drop'].forEach(e=>drop.addEventListener(e,ev=>{ev.preventDefault();drop.classList.remove('over');}));
drop.addEventListener('drop',ev=>{
  ev.preventDefault();
  drop.classList.remove('over');
  if(ev.dataTransfer&&ev.dataTransfer.items&&ev.dataTransfer.items.length){
    const items=ev.dataTransfer.items;
    let hasEntries=false;
    for(let i=0;i<items.length;i++){
      if(items[i].webkitGetAsEntry){
        const entry=items[i].webkitGetAsEntry();
        if(entry){
          hasEntries=true;
          traverseEntry(entry,'');
        }
      }
    }
    if(!hasEntries&&ev.dataTransfer.files&&ev.dataTransfer.files.length){
      enqueueFiles(ev.dataTransfer.files);
    }
  } else if(ev.dataTransfer&&ev.dataTransfer.files&&ev.dataTransfer.files.length){
    enqueueFiles(ev.dataTransfer.files);
  }
});

function traverseEntry(entry, path){
  if(entry.isFile){
    entry.file(f=>{
      const rp=path?path+'/'+f.name:f.name;
      const wrapped={file:f,name:f.name,size:f.size,relativePath:rp};
      uploadQueue.push(wrapped);
      renderQueue();
      if(!isUploading) processQueue();
    });
  } else if(entry.isDirectory){
    const reader=entry.createReader();
    const readBatch=()=>{
      reader.readEntries(entries=>{
        if(entries.length===0) return;
        for(const e of entries){
          traverseEntry(e, path?path+'/'+entry.name:entry.name);
        }
        readBatch();
      });
    };
    readBatch();
  }
}

function enqueueFiles(files){
  for(let i=0;i<files.length;i++){
    uploadQueue.push({file:files[i],name:files[i].name,size:files[i].size,relativePath:files[i].name});
  }
  renderQueue();
  if(!isUploading){
    processQueue();
  }
}

function removeFromQueue(index){
  uploadQueue.splice(index,1);
  renderQueue();
}

function clearQueue(){
  uploadQueue = [];
  renderQueue();
}

function renderQueue(){
  const qBox = document.getElementById('queue-box');
  const qList = document.getElementById('queue-list');
  const qCount = document.getElementById('queue-count');
  if(uploadQueue.length > 0){
    qBox.style.display = 'block';
    qCount.textContent = uploadQueue.length;
    qList.innerHTML = uploadQueue.map((f, i) =>
      `<div class="queue-item"><span class="queue-name" title="${escapeHtml(f.relativePath||f.name)}">${escapeHtml(f.relativePath||f.name)} (${fmtBytes(f.size)})</span><button type="button" class="btn-remove" onclick="removeFromQueue(${i})">✕</button></div>`
    ).join('');
  } else {
    qBox.style.display = 'none';
    qList.innerHTML = '';
  }
}

function cancelCurrentUpload(){
  if(currentXhr){
    currentXhr.abort();
    currentXhr = null;
  }
  st.textContent = 'Upload cancelled';
  bar.style.background = '#EF4444';
  if(cancelBtn) cancelBtn.style.display = 'none';
  setTimeout(()=>{
    isUploading = false;
    processQueue();
  }, 400);
}

function processQueue(){
  if(uploadQueue.length === 0){
    isUploading = false;
    currentXhr = null;
    currentFile = null;
    if(cancelBtn) cancelBtn.style.display = 'none';
    if(curFileEl) curFileEl.style.display = 'none';
    drop.textContent = 'Click or drag files here';
    return;
  }

  isUploading = true;
  currentFile = uploadQueue.shift();
  renderQueue();

  const displayName = currentFile.relativePath || currentFile.name;
  if(curFileEl){
    curFileEl.style.display = 'block';
    curFileEl.textContent = 'Uploading: ' + displayName + ' (' + fmtBytes(currentFile.size) + ')';
  }
  drop.textContent = 'Uploading ' + displayName + '...';

  pg.style.display = 'block';
  bar.style.width = '0%';
  bar.style.background = '#00E5FF';
  st.textContent = '0%';
  if(cancelBtn) cancelBtn.style.display = 'inline-block';

  const fd = new FormData();
  const uploadName = currentFile.relativePath || currentFile.name;
  fd.append('file', currentFile.file || currentFile, uploadName);

  currentXhr = new XMLHttpRequest();
  currentXhr.open('POST', '/upload' + (t ? '?t=' + encodeURIComponent(t) : ''));
  if(t) currentXhr.setRequestHeader('Authorization', 'Bearer ' + t);

  let lastTime = Date.now(), lastBytes = 0, smoothSpeed = 0;
  currentXhr.upload.onprogress = e => {
    if(e.lengthComputable){
      const p = Math.round(e.loaded / e.total * 100);
      bar.style.width = p + '%';
      const now = Date.now();
      const dt = (now - lastTime) / 1000;
      if(dt >= 0.5){
        const rawSpeed = (e.loaded - lastBytes) / dt;
        smoothSpeed = smoothSpeed > 0 ? 0.7 * smoothSpeed + 0.3 * rawSpeed : rawSpeed;
        lastTime = now; lastBytes = e.loaded;
        const remaining = e.total - e.loaded;
        const eta = smoothSpeed > 0 ? remaining / smoothSpeed : 0;
        const etaStr = eta > 0 ? ' \u00b7 ' + fmtDuration(eta) + ' left' : '';
        st.textContent = p + '% \u00b7 ' + fmtBytes(e.loaded) + '/' + fmtBytes(e.total) + ' \u00b7 ' + fmtSpeed(smoothSpeed) + etaStr;
      }
    }
  };

  currentXhr.onload = () => {
    if(currentXhr.status === 200){
      st.textContent = '✓ ' + currentFile.name + ' uploaded';
      bar.style.width = '100%';
      bar.style.background = '#22C55E';
      if(cancelBtn) cancelBtn.style.display = 'none';
      setTimeout(()=>{
        processQueue();
      }, 500);
    } else {
      st.textContent = 'Error: ' + (currentXhr.responseText || 'Access denied');
      bar.style.background = '#EF4444';
      if(cancelBtn) cancelBtn.style.display = 'none';
      setTimeout(()=>{
        processQueue();
      }, 1200);
    }
  };

  currentXhr.onerror = () => {
    st.textContent = 'Network error';
    bar.style.background = '#EF4444';
    if(cancelBtn) cancelBtn.style.display = 'none';
    setTimeout(()=>{
      processQueue();
    }, 1200);
  };

  currentXhr.send(fd);
}
</script></body></html>"#;

pub fn build_web_tls_config(
    key_der: &[u8],
    cert_der: &[u8],
) -> Result<Arc<ServerConfig>, Box<dyn std::error::Error + Send + Sync>> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    use rustls_pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    let cert = CertificateDer::from(cert_der.to_vec());
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_der.to_vec()));
    let config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert], key)?;
    Ok(Arc::new(config))
}

fn generate_token() -> String {
    let mut bytes = [0u8; 16];
    getrandom::getrandom(&mut bytes).expect("getrandom failed");
    hex::encode(bytes)
}

fn sanitize_filename(name: &str) -> String {
    let sanitized: String = name
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || c == '.' || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect();
    let trimmed = sanitized.trim_matches('.');
    if sanitized.is_empty() || sanitized == "." || sanitized == ".." || trimmed.is_empty() {
        let mut rand_bytes = [0u8; 4];
        let _ = getrandom::getrandom(&mut rand_bytes);
        format!("uploaded_file_{}.bin", hex::encode(rand_bytes))
    } else {
        sanitized
    }
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct WebShareItem {
    pub id: usize,
    pub name: String,
    pub size: u64,
    pub mime: String,
    pub path: String,
}

const HTML_DOWNLOAD_PAGE: &str = r#"<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>NXFR — Direct Download</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;background:#0F172A;color:#E2E8F0;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;padding:16px;box-sizing:border-box}
.card{background:#1E293B;border-radius:16px;padding:32px;max-width:540px;width:100%;box-shadow:0 4px 24px #00000066}
h1{color:#00E5FF;font-size:24px;margin:0 0 8px}
.sub{color:#94A3B8;margin:0 0 20px;font-size:14px}
.fp-box{font-size:12px;color:#94A3B8;background:#0F172A;padding:10px;border-radius:8px;word-break:break-all;margin-bottom:16px;border:1px solid #1E293B}
.file-list{margin-top:16px;display:flex;flex-direction:column;gap:12px}
.file-item{display:flex;flex-direction:column;background:#0F172A;padding:14px 16px;border-radius:8px;border:1px solid #334155}
.file-row{display:flex;align-items:center;justify-content:space-between}
.file-info{display:flex;flex-direction:column;overflow:hidden;margin-right:12px}
.file-name{font-weight:600;color:#F8FAFC;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.file-size{font-size:12px;color:#94A3B8;margin-top:2px}
.btn{background:#00E5FF;color:#0F172A;border:none;border-radius:8px;padding:8px 16px;font-weight:700;cursor:pointer;font-size:13px;text-decoration:none;display:inline-flex;align-items:center;white-space:nowrap;transition:background 0.2s}
.btn:hover{background:#38BDF8}
.btn:disabled{opacity:0.6;cursor:not-allowed}
.btn-cancel{background:#EF4444;color:#FFF}
.btn-cancel:hover{background:#DC2626}
.btn-success{background:#22C55E;color:#0F172A}
.btn-all{background:#22C55E;color:#0F172A;width:100%;justify-content:center;padding:12px;font-size:15px;margin-bottom:16px}
.btn-all:hover{background:#4ADE80}
.btn-all:disabled{opacity:0.6;cursor:not-allowed}
.pg-track{width:100%;height:6px;background:#334155;border-radius:3px;margin-top:10px;overflow:hidden;display:none}
.pg-bar{height:100%;background:#00E5FF;width:0%;transition:width 0.15s ease}
.pg-status{font-size:11px;color:#94A3B8;margin-top:4px;display:none;font-family:monospace}
.pin-input{width:100%;box-sizing:border-box;background:#0F172A;border:2px solid #334155;border-radius:8px;color:#F8FAFC;font-size:24px;font-family:monospace;text-align:center;letter-spacing:6px;padding:12px;outline:none;transition:border-color 0.2s}
.pin-input:focus{border-color:#00E5FF}
.pin-err{color:#EF4444;font-size:13px;margin-top:10px;display:none;text-align:center;font-weight:600}
</style></head><body>

<div class="card" id="pin-card" style="display:none">
<h1>NXFR Protected Share</h1>
<p class="sub">The sender set a security PIN for this share. Enter the PIN to unlock and download files.</p>
<div class="fp-box">
Connected Device Fingerprint:<br>
<strong style="color:#00E5FF;font-family:monospace;font-size:11px;">{{FINGERPRINT}}</strong>
</div>
<div style="margin:24px 0 16px">
<input type="text" id="pin-input" class="pin-input" inputmode="numeric" pattern="[0-9]*" maxlength="8" placeholder="Enter PIN" autocomplete="off">
<div id="pin-err" class="pin-err"></div>
</div>
<button class="btn btn-all" id="btn-unlock" onclick="unlockShareWithPin()" style="margin-bottom:0">Unlock Downloads</button>
</div>

<div class="card" id="main-card">
<h1>NXFR Direct Download</h1>
<p class="sub">Download files shared directly from the peer device</p>
<div class="fp-box">
Connected Device Fingerprint:<br>
<strong style="color:#00E5FF;font-family:monospace;font-size:11px;">{{FINGERPRINT}}</strong>
</div>
<button class="btn btn-all" id="btn-all" onclick="downloadAll()">Download All Files ({{TOTAL_FILES}})</button>
<div class="file-list" id="file-list"></div>
</div>
<script>
const hasPin = {{HAS_PIN}};
const manifest = {{MANIFEST_JSON}};
const hashToken = location.hash.replace(/^#t=/, '').replace(/^#/, '');
const params = new URLSearchParams(location.search);
let t = hashToken || params.get('t') || '';

if (hasPin && !t) {
  document.getElementById('pin-card').style.display = 'block';
  document.getElementById('main-card').style.display = 'none';
  const pi = document.getElementById('pin-input');
  setTimeout(() => pi.focus(), 100);
  pi.addEventListener('keydown', e => { if (e.key === 'Enter') unlockShareWithPin(); });
} else {
  document.getElementById('pin-card').style.display = 'none';
  document.getElementById('main-card').style.display = 'block';
}

async function unlockShareWithPin() {
  const pinInput = document.getElementById('pin-input');
  const pinVal = pinInput.value.trim();
  const pinErr = document.getElementById('pin-err');
  const btnUnlock = document.getElementById('btn-unlock');

  if (!pinVal) {
    pinErr.style.display = 'block';
    pinErr.textContent = 'Please enter the PIN';
    return;
  }

  btnUnlock.disabled = true;
  btnUnlock.textContent = 'Verifying...';
  pinErr.style.display = 'none';

  try {
    const res = await fetch('/auth', {
      headers: { 'Authorization': 'Bearer ' + pinVal }
    });
    if (res.ok) {
      t = pinVal;
      document.getElementById('pin-card').style.display = 'none';
      document.getElementById('main-card').style.display = 'block';
    } else {
      pinErr.style.display = 'block';
      pinErr.textContent = '❌ Incorrect PIN — Access Denied';
      pinInput.value = '';
      pinInput.focus();
    }
  } catch (err) {
    pinErr.style.display = 'block';
    pinErr.textContent = 'Network error \u2014 try again';
  } finally {
    btnUnlock.disabled = false;
    btnUnlock.textContent = 'Unlock Downloads';
  }
}

function fmtBytes(b){
  if(!b||b<=0) return '0 B';
  const u=['B','KB','MB','GB','TB'];
  const i=Math.floor(Math.log(b)/Math.log(1024));
  return (b/Math.pow(1024,i)).toFixed(1)+' '+u[i];
}
function fmtSpeed(bps){
  if(bps<=0) return '...';
  if(bps>=1073741824) return (bps/1073741824).toFixed(1)+' GB/s';
  if(bps>=1048576) return (bps/1048576).toFixed(1)+' MB/s';
  if(bps>=1024) return (bps/1024).toFixed(0)+' KB/s';
  return bps.toFixed(0)+' B/s';
}
function fmtDuration(s){
  if(s<60) return Math.ceil(s)+'s';
  const m=Math.floor(s/60),r=Math.ceil(s%60);
  return m+':'+String(r).padStart(2,'0');
}

const BLOB_THRESHOLD = 104857600; // 100MB
const downloadControllers = {};

const list = document.getElementById('file-list');
manifest.forEach(item => {
  const div = document.createElement('div');
  div.className = 'file-item';
  div.id = `item-${item.id}`;

  const row = document.createElement('div');
  row.className = 'file-row';

  const info = document.createElement('div');
  info.className = 'file-info';

  const nameEl = document.createElement('div');
  nameEl.className = 'file-name';
  nameEl.title = item.name;
  nameEl.textContent = item.name;

  const sizeEl = document.createElement('div');
  sizeEl.className = 'file-size';
  sizeEl.textContent = fmtBytes(item.size);

  info.appendChild(nameEl);
  info.appendChild(sizeEl);

  const btn = document.createElement('button');
  btn.className = 'btn';
  btn.id = `btn-${item.id}`;
  btn.textContent = 'Download';
  btn.onclick = () => downloadItem(item.id);

  row.appendChild(info);
  row.appendChild(btn);
  div.appendChild(row);

  const pgTrack = document.createElement('div');
  pgTrack.className = 'pg-track';
  pgTrack.id = `pg-track-${item.id}`;
  const pgBar = document.createElement('div');
  pgBar.className = 'pg-bar';
  pgBar.id = `pg-bar-${item.id}`;
  pgTrack.appendChild(pgBar);
  div.appendChild(pgTrack);

  const pgStatus = document.createElement('div');
  pgStatus.className = 'pg-status';
  pgStatus.id = `pg-status-${item.id}`;
  div.appendChild(pgStatus);

  list.appendChild(div);
});

async function downloadItem(id) {
  const item = manifest.find(i => i.id === id);
  if (!item) return;

  const btn = document.getElementById(`btn-${id}`);
  const pgTrack = document.getElementById(`pg-track-${id}`);
  const pgBar = document.getElementById(`pg-bar-${id}`);
  const pgStatus = document.getElementById(`pg-status-${id}`);

  // If already downloading and clicked, cancel this download
  if (downloadControllers[id]) {
    downloadControllers[id].abort();
    delete downloadControllers[id];
    if (pgStatus) { pgStatus.textContent = 'Download cancelled'; pgStatus.style.color = '#94A3B8'; }
    if (pgBar) pgBar.style.background = '#334155';
    if (btn) { btn.textContent = 'Download'; btn.className = 'btn'; btn.disabled = false; }
    return;
  }

  const url = `/dl/${item.id}` + (t ? `?t=${encodeURIComponent(t)}` : '');

  // P1: Large files (>100MB) — hand off to browser native download manager
  if (item.size > BLOB_THRESHOLD) {
    if (btn) { btn.textContent = 'Downloading...'; btn.disabled = true; btn.className = 'btn'; }
    if (pgTrack) pgTrack.style.display = 'block';
    if (pgStatus) { pgStatus.style.display = 'block'; pgStatus.textContent = 'Downloading via browser...'; pgStatus.style.color = '#94A3B8'; }
    if (pgBar) { pgBar.style.width = '100%'; pgBar.style.background = '#00E5FF'; }
    window.location.assign(url + (url.includes('?') ? '&' : '?') + 'dl=1');
    setTimeout(() => {
      if (btn) { btn.textContent = 'Downloaded ✓'; btn.className = 'btn btn-success'; btn.disabled = false; }
      if (pgStatus) pgStatus.textContent = 'Sent to browser download manager';
    }, 3000);
    return;
  }

  // Files <=100MB: fetch + ReadableStream with progress and speed/ETA
  const controller = new AbortController();
  downloadControllers[id] = controller;

  if (btn) { btn.textContent = 'Cancel'; btn.className = 'btn btn-cancel'; btn.disabled = false; }
  if (pgTrack) pgTrack.style.display = 'block';
  if (pgBar) { pgBar.style.width = '0%'; pgBar.style.background = '#00E5FF'; }
  if (pgStatus) { pgStatus.style.display = 'block'; pgStatus.style.color = '#94A3B8'; pgStatus.textContent = 'Connecting...'; }

  const headers = {};
  if (t) headers['Authorization'] = 'Bearer ' + t;

  try {
    const response = await fetch(url, { headers, signal: controller.signal });
    if (!response.ok) {
      const errText = await response.text().catch(() => 'Download failed');
      throw new Error(`HTTP ${response.status}: ${errText}`);
    }

    const contentLength = response.headers.get('Content-Length');
    const totalBytes = contentLength ? parseInt(contentLength, 10) : item.size;

    const reader = response.body.getReader();
    const chunks = [];
    let receivedBytes = 0;
    let lastTime = Date.now(), lastBytes = 0, smoothSpeed = 0;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      receivedBytes += value.length;

      if (totalBytes > 0) {
        const pct = Math.min(100, Math.round((receivedBytes / totalBytes) * 100));
        if (pgBar) pgBar.style.width = pct + '%';
        const now = Date.now();
        const dt = (now - lastTime) / 1000;
        if (dt >= 0.5) {
          const rawSpeed = (receivedBytes - lastBytes) / dt;
          smoothSpeed = smoothSpeed > 0 ? 0.7 * smoothSpeed + 0.3 * rawSpeed : rawSpeed;
          lastTime = now; lastBytes = receivedBytes;
          const remaining = totalBytes - receivedBytes;
          const eta = smoothSpeed > 0 ? remaining / smoothSpeed : 0;
          const etaStr = eta > 0 ? ' \u00b7 ' + fmtDuration(eta) + ' left' : '';
          if (pgStatus) pgStatus.textContent = `${pct}% \u00b7 ${fmtBytes(receivedBytes)}/${fmtBytes(totalBytes)} \u00b7 ${fmtSpeed(smoothSpeed)}${etaStr}`;
        }
      } else {
        if (pgStatus) pgStatus.textContent = `${fmtBytes(receivedBytes)}`;
      }
    }

    if (pgBar) { pgBar.style.width = '100%'; pgBar.style.background = '#22C55E'; }
    if (pgStatus) pgStatus.textContent = 'Complete \u2713';

    const blob = new Blob(chunks, { type: item.mime || 'application/octet-stream' });
    const objectUrl = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = objectUrl;
    a.download = item.name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => window.URL.revokeObjectURL(objectUrl), 60000);

    if (btn) { btn.textContent = 'Downloaded \u2713'; btn.className = 'btn btn-success'; btn.disabled = false; }
  } catch (err) {
    if (err.name === 'AbortError') {
      if (pgStatus) { pgStatus.style.display = 'block'; pgStatus.textContent = 'Download cancelled'; pgStatus.style.color = '#94A3B8'; }
      if (pgBar) pgBar.style.background = '#334155';
      if (btn) { btn.textContent = 'Download'; btn.className = 'btn'; btn.disabled = false; }
    } else {
      if (pgStatus) { pgStatus.style.display = 'block'; pgStatus.textContent = 'Error: ' + err.message; pgStatus.style.color = '#EF4444'; }
      if (pgBar) pgBar.style.background = '#EF4444';
      if (btn) { btn.disabled = false; btn.textContent = 'Retry'; btn.className = 'btn'; }
    }
  } finally {
    delete downloadControllers[id];
  }
}

async function downloadAll() {
  const btnAll = document.getElementById('btn-all');
  if (btnAll) { btnAll.disabled = true; btnAll.textContent = 'Preparing ZIP...'; }
  const url = '/dl/all.zip' + (t ? '?t=' + encodeURIComponent(t) : '');
  window.location.assign(url);
  setTimeout(() => {
    if (btnAll) { btnAll.disabled = false; btnAll.textContent = 'Download All as ZIP'; }
  }, 3000);
}
</script></body></html>"#;

pub struct WebServerHandle {
    pub handle: JoinHandle<()>,
    pub token: String,
    pub port: u16,
    pub cancel: CancellationToken,
    pub active_transfers: Arc<AtomicUsize>,
    pub last_activity: Arc<RwLock<Instant>>,
}

impl WebServerHandle {
    pub fn stop(&self) {
        self.cancel.cancel();
    }

    pub fn is_stopped(&self) -> bool {
        self.cancel.is_cancelled()
    }

    pub fn active_transfers_count(&self) -> usize {
        self.active_transfers.load(Ordering::SeqCst)
    }
}

pub struct WebServer {
    pub token: String,
    pub port: u16,
    pub cancel: CancellationToken,
    pub pin: Option<String>,
    pub expiry_duration: Duration,
    pub last_activity: Arc<RwLock<Instant>>,
    pub active_transfers: Arc<AtomicUsize>,
    pub receive_dir: PathBuf,
    pub max_file_size: u64,
    pub fingerprint: String,
    pub share_manifest: Option<Vec<WebShareItem>>,
    pub failed_attempts: Arc<Mutex<HashMap<IpAddr, (u32, Instant)>>>,
    pub max_downloads: Option<u32>,
    pub download_count: Arc<AtomicUsize>,
}

impl WebServer {
    pub fn new(
        receive_dir: PathBuf,
        port: u16,
        max_file_size: u64,
        pin: Option<String>,
        fingerprint: String,
        share_manifest: Option<Vec<WebShareItem>>,
    ) -> (Self, CancellationToken) {
        Self::new_with_expiry(
            receive_dir,
            port,
            max_file_size,
            pin,
            fingerprint,
            share_manifest,
            get_default_expiry_duration(),
            None,
        )
    }

    pub fn new_with_expiry(
        receive_dir: PathBuf,
        port: u16,
        max_file_size: u64,
        pin: Option<String>,
        fingerprint: String,
        share_manifest: Option<Vec<WebShareItem>>,
        expiry_duration: Duration,
        max_downloads: Option<u32>,
    ) -> (Self, CancellationToken) {
        let cancel = CancellationToken::new();
        let token = generate_token();
        let last_activity = Arc::new(RwLock::new(Instant::now()));
        let active_transfers = Arc::new(AtomicUsize::new(0));
        let download_count = Arc::new(AtomicUsize::new(0));
        (
            Self {
                token,
                port,
                cancel: cancel.clone(),
                pin,
                expiry_duration,
                last_activity,
                active_transfers,
                receive_dir,
                max_file_size,
                fingerprint,
                share_manifest,
                failed_attempts: Arc::new(Mutex::new(HashMap::new())),
                max_downloads,
                download_count,
            },
            cancel,
        )
    }

    pub async fn bump_activity(&self) {
        let mut act = self.last_activity.write().await;
        *act = Instant::now();
    }

    pub async fn start(
        key_der: &[u8],
        cert_der: &[u8],
        receive_dir: PathBuf,
        preferred_port: u16,
        pin: Option<String>,
    ) -> Result<WebServerHandle, Box<dyn std::error::Error + Send + Sync>> {
        Self::start_with_expiry(
            key_der,
            cert_der,
            receive_dir,
            preferred_port,
            pin,
            get_default_expiry_duration(),
        )
        .await
    }

    pub async fn start_with_expiry(
        key_der: &[u8],
        cert_der: &[u8],
        receive_dir: PathBuf,
        preferred_port: u16,
        pin: Option<String>,
        expiry_duration: Duration,
    ) -> Result<WebServerHandle, Box<dyn std::error::Error + Send + Sync>> {
        let mut listener = None;
        let mut actual_port = preferred_port;

        for p in preferred_port..(preferred_port + MAX_PORT_ATTEMPTS) {
            match TcpListener::bind(("0.0.0.0", p)).await {
                Ok(l) => {
                    listener = Some(l);
                    actual_port = p;
                    break;
                }
                Err(e) => {
                    log::warn!("[nxfr-web] Port {} bound failed: {}, trying next...", p, e);
                }
            }
        }

        let listener = match listener {
            Some(l) => l,
            None => {
                return Err(format!(
                    "Could not bind to any port in range {}..{}",
                    preferred_port,
                    preferred_port + MAX_PORT_ATTEMPTS
                )
                .into())
            }
        };

        let fp_bytes = nxfr_crypto::identity::device_id_from_cert(cert_der).unwrap_or([0u8; 32]);
        let fp_formatted = fp_bytes
            .iter()
            .map(|b| format!("{:02X}", b))
            .collect::<Vec<_>>()
            .join(":");

        let (server, cancel) = Self::new_with_expiry(
            receive_dir,
            actual_port,
            MAX_UPLOAD_LIMIT,
            pin,
            fp_formatted,
            None,
            expiry_duration,
            None,
        );
        let token = server.token.clone();
        let last_activity_clone = server.last_activity.clone();
        let active_transfers_clone = server.active_transfers.clone();

        let active_transfers_handle = server.active_transfers.clone();
        let last_activity_handle = server.last_activity.clone();

        let tls_config = build_web_tls_config(key_der, cert_der)?;
        let acceptor = TlsAcceptor::from(tls_config);
        let server_arc = Arc::new(server);

        let token_for_log = token.clone();
        let cancel_clone = cancel.clone();
        let join_handle = tokio::spawn(async move {
            log::info!(
                "[nxfr-web] Web upload server started on port {}, token={}",
                actual_port,
                token_for_log
            );

            loop {
                let (time_to_wait, active_count) = {
                    let last = *last_activity_clone.read().await;
                    let elapsed = last.elapsed();
                    let active = active_transfers_clone.load(Ordering::SeqCst);
                    if active > 0 {
                        (Duration::from_millis(250), active)
                    } else if elapsed >= expiry_duration {
                        (Duration::ZERO, 0)
                    } else {
                        (expiry_duration - elapsed, 0)
                    }
                };

                if time_to_wait.is_zero() && active_count == 0 {
                    log::info!(
                        "[nxfr-web] Server inactivity expiry reached ({:?}). Stopping server listener.",
                        expiry_duration
                    );
                    break;
                }

                tokio::select! {
                    _ = cancel_clone.cancelled() => {
                        log::info!("[nxfr-web] Server cancel token triggered. Stopping server.");
                        break;
                    }
                    _ = tokio::time::sleep(time_to_wait) => {}
                    res = listener.accept() => {
                        match res {
                            Ok((stream, addr)) => {
                                let acceptor = acceptor.clone();
                                let server_arc = server_arc.clone();
                                tokio::spawn(async move {
                                    let task_result = std::panic::AssertUnwindSafe(async {
                                        match acceptor.accept(stream).await {
                                            Ok(mut tls_stream) => {
                                                if let Err(e) = server_arc.handle_connection(&mut tls_stream, addr.ip()).await {
                                                    log::debug!("[nxfr-web] [{}] Connection finished with: {}", addr.ip(), e);
                                                }
                                            }
                                            Err(e) => {
                                                log::warn!("[nxfr-web] [{}] TLS handshake failed: {}", addr.ip(), e);
                                            }
                                        }
                                    })
                                    .catch_unwind()
                                    .await;

                                    if let Err(panic_info) = task_result {
                                        log::error!("[nxfr-web] [{}] Request handler panicked: {:?}", addr.ip(), panic_info);
                                    }
                                });
                            }
                            Err(e) => {
                                log::warn!("[nxfr-web] Accept error: {}", e);
                            }
                        }
                    }
                }
            }

            // Stop accepting new connections. Drain in-flight transfers.
            log::info!("[nxfr-web] Listener closed. Draining in-flight transfers...");
            let drain_start = Instant::now();
            while active_transfers_clone.load(Ordering::SeqCst) > 0 {
                if drain_start.elapsed() > Duration::from_secs(300) {
                    log::warn!("[nxfr-web] Drain timeout exceeded (300s). Forcing server stop.");
                    break;
                }
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            log::info!("[nxfr-web] In-flight transfers drained. Server shutdown complete.");
            cancel_clone.cancel();
        });

        Ok(WebServerHandle {
            handle: join_handle,
            token,
            port: actual_port,
            cancel,
            active_transfers: active_transfers_handle,
            last_activity: last_activity_handle,
        })
    }

    pub async fn start_share(
        key_der: &[u8],
        cert_der: &[u8],
        preferred_port: u16,
        pin: Option<String>,
        share_manifest: Vec<WebShareItem>,
    ) -> Result<WebServerHandle, Box<dyn std::error::Error + Send + Sync>> {
        Self::start_share_with_expiry(
            key_der,
            cert_der,
            preferred_port,
            pin,
            share_manifest,
            get_default_expiry_duration(),
        )
        .await
    }

    pub async fn start_share_with_expiry(
        key_der: &[u8],
        cert_der: &[u8],
        preferred_port: u16,
        pin: Option<String>,
        share_manifest: Vec<WebShareItem>,
        expiry_duration: Duration,
    ) -> Result<WebServerHandle, Box<dyn std::error::Error + Send + Sync>> {
        let mut listener = None;
        let mut actual_port = preferred_port;

        for p in preferred_port..(preferred_port + MAX_PORT_ATTEMPTS) {
            match TcpListener::bind(("0.0.0.0", p)).await {
                Ok(l) => {
                    listener = Some(l);
                    actual_port = p;
                    break;
                }
                Err(e) => {
                    log::warn!(
                        "[nxfr-web] Share port {} bound failed: {}, trying next...",
                        p,
                        e
                    );
                }
            }
        }

        let listener = match listener {
            Some(l) => l,
            None => {
                return Err(format!(
                    "Could not bind to any port in range {}..{}",
                    preferred_port,
                    preferred_port + MAX_PORT_ATTEMPTS
                )
                .into())
            }
        };

        let fp_bytes = nxfr_crypto::identity::device_id_from_cert(cert_der).unwrap_or([0u8; 32]);
        let fp_formatted = fp_bytes
            .iter()
            .map(|b| format!("{:02X}", b))
            .collect::<Vec<_>>()
            .join(":");

        let (server, cancel) = Self::new_with_expiry(
            PathBuf::new(),
            actual_port,
            MAX_UPLOAD_LIMIT,
            pin,
            fp_formatted,
            Some(share_manifest),
            expiry_duration,
            None,
        );
        let token = server.token.clone();
        let last_activity_clone = server.last_activity.clone();
        let active_transfers_clone = server.active_transfers.clone();

        let active_transfers_handle = server.active_transfers.clone();
        let last_activity_handle = server.last_activity.clone();

        let tls_config = build_web_tls_config(key_der, cert_der)?;
        let acceptor = TlsAcceptor::from(tls_config);
        let server_arc = Arc::new(server);

        let token_for_log = token.clone();
        let cancel_clone = cancel.clone();
        let join_handle = tokio::spawn(async move {
            log::info!(
                "[nxfr-web] Web share server started on port {}, token={}",
                actual_port,
                token_for_log
            );

            loop {
                let (time_to_wait, active_count) = {
                    let last = *last_activity_clone.read().await;
                    let elapsed = last.elapsed();
                    let active = active_transfers_clone.load(Ordering::SeqCst);
                    if active > 0 {
                        (Duration::from_millis(250), active)
                    } else if elapsed >= expiry_duration {
                        (Duration::ZERO, 0)
                    } else {
                        (expiry_duration - elapsed, 0)
                    }
                };

                if time_to_wait.is_zero() && active_count == 0 {
                    log::info!(
                        "[nxfr-web] Share server inactivity expiry reached ({:?}). Stopping server listener.",
                        expiry_duration
                    );
                    break;
                }

                tokio::select! {
                    _ = cancel_clone.cancelled() => {
                        log::info!("[nxfr-web] Share server cancel token triggered. Stopping server.");
                        break;
                    }
                    _ = tokio::time::sleep(time_to_wait) => {}
                    res = listener.accept() => {
                        match res {
                            Ok((stream, addr)) => {
                                let acceptor = acceptor.clone();
                                let server_arc = server_arc.clone();
                                tokio::spawn(async move {
                                    let task_result = std::panic::AssertUnwindSafe(async {
                                        match acceptor.accept(stream).await {
                                            Ok(mut tls_stream) => {
                                                if let Err(e) = server_arc.handle_connection(&mut tls_stream, addr.ip()).await {
                                                    log::debug!("[nxfr-web] [{}] Connection finished with: {}", addr.ip(), e);
                                                }
                                            }
                                            Err(e) => {
                                                log::warn!("[nxfr-web] [{}] TLS handshake failed: {}", addr.ip(), e);
                                            }
                                        }
                                    })
                                    .catch_unwind()
                                    .await;

                                    if let Err(panic_info) = task_result {
                                        log::error!("[nxfr-web] [{}] Request handler panicked: {:?}", addr.ip(), panic_info);
                                    }
                                });
                            }
                            Err(e) => {
                                log::warn!("[nxfr-web] Accept error: {}", e);
                            }
                        }
                    }
                }
            }

            // Stop accepting new connections. Drain in-flight transfers.
            log::info!("[nxfr-web] Listener closed. Draining in-flight transfers...");
            let drain_start = Instant::now();
            while active_transfers_clone.load(Ordering::SeqCst) > 0 {
                if drain_start.elapsed() > Duration::from_secs(300) {
                    log::warn!("[nxfr-web] Drain timeout exceeded (300s). Forcing server stop.");
                    break;
                }
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            log::info!("[nxfr-web] In-flight transfers drained. Server shutdown complete.");
            cancel_clone.cancel();
        });

        Ok(WebServerHandle {
            handle: join_handle,
            token,
            port: actual_port,
            cancel,
            active_transfers: active_transfers_handle,
            last_activity: last_activity_handle,
        })
    }

    async fn send_response(
        stream: &mut tokio_rustls::server::TlsStream<TcpStream>,
        status: &str,
        content_type: &str,
        body: &[u8],
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let header = format!(
            "HTTP/1.1 {}\r\n\
             Content-Type: {}\r\n\
             Content-Length: {}\r\n\
             Strict-Transport-Security: max-age=31536000; includeSubDomains\r\n\
             Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'\r\n\
             X-Content-Type-Options: nosniff\r\n\
             X-Frame-Options: DENY\r\n\
             Connection: close\r\n\
             \r\n",
            status,
            content_type,
            body.len()
        );
        stream.write_all(header.as_bytes()).await?;
        if !body.is_empty() {
            stream.write_all(body).await?;
        }
        stream.flush().await?;
        let _ = stream.shutdown().await;
        Ok(())
    }

    async fn handle_connection(
        &self,
        stream: &mut tokio_rustls::server::TlsStream<TcpStream>,
        ip: IpAddr,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        if self.cancel.is_cancelled() {
            log::warn!(
                "[nxfr-web] [{}] Connection rejected: server is shutting down",
                ip
            );
            return Ok(());
        }

        self.bump_activity().await;

        // Rate limit / IP ban check
        {
            let mut failed = self.failed_attempts.lock().await;
            if let Some((count, last_fail)) = failed.get(&ip) {
                if *count >= MAX_FAILED_ATTEMPTS {
                    if last_fail.elapsed() < BLOCK_DURATION {
                        log::warn!("[nxfr-web] [{}] 403 Forbidden: IP temporarily blocked due to excessive failed attempts", ip);
                        let body =
                            b"{\"error\": \"Too many failed attempts. Temporarily blocked.\"}";
                        return Self::send_response(
                            stream,
                            "403 Forbidden",
                            "application/json",
                            body,
                        )
                        .await;
                    } else {
                        failed.remove(&ip);
                    }
                }
            }
        }

        let mut headers_buf = Vec::new();
        let mut buf = [0u8; 1024];
        let body_start: usize;

        loop {
            let n = stream.read(&mut buf).await?;
            if n == 0 {
                return Ok(());
            }
            headers_buf.extend_from_slice(&buf[..n]);

            if let Some(pos) = headers_buf
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
            {
                body_start = pos + 4;
                break;
            }
            if headers_buf.len() > 8192 {
                log::warn!("[nxfr-web] [{}] Request headers exceeded 8KB limit", ip);
                return Ok(());
            }
        }

        let headers_str = String::from_utf8_lossy(&headers_buf[..body_start]);
        let mut lines = headers_str.lines();
        let request_line = lines.next().unwrap_or("");

        let mut parts = request_line.split_whitespace();
        let method = parts.next().unwrap_or("");
        let raw_path = parts.next().unwrap_or("");

        let (path, query) = match raw_path.find('?') {
            Some(idx) => (&raw_path[..idx], &raw_path[idx + 1..]),
            None => (raw_path, ""),
        };

        log::info!("[nxfr-web] [{}] HTTP Request: {} {}", ip, method, path);

        if method == "GET" && path == "/" {
            let has_pin = self.pin.is_some();
            if let Some(manifest) = &self.share_manifest {
                let manifest_json =
                    serde_json::to_string(manifest).unwrap_or_else(|_| "[]".to_string());
                let page = HTML_DOWNLOAD_PAGE
                    .replace("{{FINGERPRINT}}", &self.fingerprint)
                    .replace("{{MANIFEST_JSON}}", &manifest_json)
                    .replace("{{HAS_PIN}}", if has_pin { "true" } else { "false" });
                log::info!(
                    "[nxfr-web] [{}] 200 OK: Served download portal ({} items, {} bytes)",
                    ip,
                    manifest.len(),
                    page.len()
                );
                return Self::send_response(
                    stream,
                    "200 OK",
                    "text/html; charset=utf-8",
                    page.as_bytes(),
                )
                .await;
            } else {
                let page = HTML_PAGE
                    .replace("{{FINGERPRINT}}", &self.fingerprint)
                    .replace("{{HAS_PIN}}", if has_pin { "true" } else { "false" });
                log::info!(
                    "[nxfr-web] [{}] 200 OK: Served upload portal ({} bytes)",
                    ip,
                    page.len()
                );
                return Self::send_response(
                    stream,
                    "200 OK",
                    "text/html; charset=utf-8",
                    page.as_bytes(),
                )
                .await;
            }
        }

        if method == "GET" && path == "/auth" {
            let mut auth_token: Option<String> = None;
            for line in lines.by_ref() {
                let lower = line.to_lowercase();
                if lower.starts_with("authorization: bearer ") {
                    auth_token = Some(line[22..].trim().to_string());
                }
            }
            if auth_token.is_none() && !query.is_empty() {
                for pair in query.split('&') {
                    if let Some((k, v)) = pair.split_once('=') {
                        if k == "t" {
                            auth_token = Some(v.to_string());
                        }
                    }
                }
            }

            let valid = match &auth_token {
                Some(tok) => tok == &self.token || self.pin.as_ref() == Some(tok),
                None => false,
            };

            if valid {
                let body = b"{\"status\": \"authenticated\"}";
                return Self::send_response(stream, "200 OK", "application/json", body).await;
            } else {
                // Record failure for rate limiting (same logic as /upload)
                {
                    let mut failed = self.failed_attempts.lock().await;
                    let entry = failed.entry(ip).or_insert((0, Instant::now()));
                    if entry.1.elapsed() > RATE_LIMIT_WINDOW {
                        entry.0 = 1;
                        entry.1 = Instant::now();
                    } else {
                        entry.0 += 1;
                        entry.1 = Instant::now();
                    }
                }
                log::warn!("[nxfr-web] [{}] 403 Forbidden: Invalid PIN on /auth", ip);
                let body = b"{\"error\": \"Invalid PIN\"}";
                return Self::send_response(stream, "403 Forbidden", "application/json", body)
                    .await;
            }
        }

        if method == "GET" && path.starts_with("/dl/") {
            let mut auth_token: Option<String> = None;
            let mut range_header: Option<String> = None;
            for line in lines {
                let lower = line.to_lowercase();
                if lower.starts_with("authorization: bearer ") {
                    auth_token = Some(line[22..].trim().to_string());
                }
                if lower.starts_with("range:") {
                    range_header = Some(line[6..].trim().to_string());
                }
            }
            if auth_token.is_none() && !query.is_empty() {
                for pair in query.split('&') {
                    if let Some((k, v)) = pair.split_once('=') {
                        if k == "t" {
                            auth_token = Some(v.to_string());
                        }
                    }
                }
            }

            let valid = match &auth_token {
                Some(tok) => tok == &self.token || self.pin.as_ref() == Some(tok),
                None => false,
            };

            if !valid {
                log::warn!(
                    "[nxfr-web] [{}] 403 Forbidden: Invalid token for download '{}'",
                    ip,
                    path
                );
                let body = b"{\"error\": \"Invalid token or PIN\"}";
                return Self::send_response(stream, "403 Forbidden", "application/json", body)
                    .await;
            }

            // P3: Handle /dl/all.zip — streamed ZIP of all shared files
            if path == "/dl/all.zip" {
                let manifest = match &self.share_manifest {
                    Some(m) => m,
                    None => {
                        let body = b"{\"error\": \"Download mode not active\"}";
                        return Self::send_response(stream, "404 Not Found", "application/json", body).await;
                    }
                };

                let _transfer_guard = ActiveTransferGuard::new(self.active_transfers.clone());

                let header = "HTTP/1.1 200 OK\r\n\
                     Content-Type: application/zip\r\n\
                     Content-Disposition: attachment; filename=\"NXFR_Share.zip\"\r\n\
                     Transfer-Encoding: chunked\r\n\
                     Connection: close\r\n\
                     \r\n";
                stream.write_all(header.as_bytes()).await?;

                // We'll collect central directory entries as we go
                struct ZipEntry {
                    filename: Vec<u8>,
                    file_size: u64,
                    crc32: u32,
                    local_offset: u64,
                }
                let mut entries: Vec<ZipEntry> = Vec::new();
                let mut offset: u64 = 0;

                for item in manifest.iter() {
                    let file_path = PathBuf::from(&item.path);
                    if !file_path.exists() {
                        log::warn!("[nxfr-web] [{}] ZIP: skipping missing file '{}'", ip, item.name);
                        continue;
                    }

                    let filename = sanitize_filename(&item.name);
                    let filename_bytes = filename.as_bytes().to_vec();

                    // Write local file header
                    let local_offset = offset;
                    write_zip_local_header(stream, &filename_bytes, item.size).await?;
                    offset += 30 + filename_bytes.len() as u64;

                    // Stream file data and compute CRC32
                    let mut f = tokio::fs::File::open(&file_path).await?;
                    let mut file_buf = [0u8; 65536];
                    let mut hasher = crc32fast::Hasher::new();
                    let mut file_written: u64 = 0;
                    loop {
                        let n = f.read(&mut file_buf).await?;
                        if n == 0 { break; }
                        stream.write_all(&file_buf[..n]).await?;
                        hasher.update(&file_buf[..n]);
                        file_written += n as u64;
                        self.bump_activity().await;
                    }
                    let crc = hasher.finalize();
                    offset += file_written;

                    // Write data descriptor
                    write_zip_data_descriptor(stream, crc, file_written).await?;
                    offset += 16;

                    entries.push(ZipEntry {
                        filename: filename_bytes,
                        file_size: file_written,
                        crc32: crc,
                        local_offset,
                    });
                }

                // Write central directory
                let central_dir_offset = offset;
                for entry in &entries {
                    write_zip_central_entry(
                        stream,
                        &entry.filename,
                        entry.file_size,
                        entry.crc32,
                        entry.local_offset,
                    ).await?;
                    offset += 46 + entry.filename.len() as u64;
                }
                let central_dir_size = offset - central_dir_offset;

                // Write EOCD
                write_zip_eocd(
                    stream,
                    entries.len() as u16,
                    central_dir_size as u32,
                    central_dir_offset as u32,
                ).await?;

                stream.flush().await?;
                let _ = stream.shutdown().await;

                // P7: Count this as a download for quota purposes
                let count = self.download_count.fetch_add(1, Ordering::SeqCst) + 1;
                if let Some(max) = self.max_downloads {
                    if count as u32 >= max {
                        log::info!("[nxfr-web] Download quota reached ({}/{}), shutting down", count, max);
                        self.cancel.cancel();
                    }
                }

                log::info!(
                    "[nxfr-web] [{}] 200 OK: Streamed ZIP archive ({} files)",
                    ip,
                    entries.len()
                );
                return Ok(());
            }

            let id_str = &path[4..];
            let id: usize = match id_str.parse() {
                Ok(i) => i,
                Err(_) => {
                    log::warn!(
                        "[nxfr-web] [{}] 404 Not Found: Invalid file id '{}'",
                        ip,
                        id_str
                    );
                    let body = b"{\"error\": \"Invalid file id format\"}";
                    return Self::send_response(stream, "404 Not Found", "application/json", body)
                        .await;
                }
            };

            let manifest = match &self.share_manifest {
                Some(m) => m,
                None => {
                    log::warn!("[nxfr-web] [{}] 404 Not Found: Share mode not active", ip);
                    let body = b"{\"error\": \"Download mode not active\"}";
                    return Self::send_response(stream, "404 Not Found", "application/json", body)
                        .await;
                }
            };

            let item = match manifest.iter().find(|i| i.id == id) {
                Some(i) => i,
                None => {
                    log::warn!(
                        "[nxfr-web] [{}] 404 Not Found: Item id {} not in manifest",
                        ip,
                        id
                    );
                    let body = b"{\"error\": \"File not found in manifest\"}";
                    return Self::send_response(stream, "404 Not Found", "application/json", body)
                        .await;
                }
            };

            let file_path = PathBuf::from(&item.path);
            if !file_path.exists() {
                log::error!(
                    "[nxfr-web] [{}] 404 Not Found: Item '{}' missing from disk at {}",
                    ip,
                    item.name,
                    item.path
                );
                let body = b"{\"error\": \"File missing on disk\"}";
                return Self::send_response(stream, "404 Not Found", "application/json", body)
                    .await;
            }

            let mut f = match tokio::fs::File::open(&file_path).await {
                Ok(f) => f,
                Err(err) => {
                    log::error!(
                        "[nxfr-web] [{}] 500 Internal Error opening '{}': {}",
                        ip,
                        item.name,
                        err
                    );
                    let body = b"{\"error\": \"Failed to read file from storage\"}";
                    return Self::send_response(
                        stream,
                        "500 Internal Error",
                        "application/json",
                        body,
                    )
                    .await;
                }
            };

            let mime = if item.mime.is_empty() {
                "application/octet-stream"
            } else {
                &item.mime
            };
            let safe_name = sanitize_filename(&item.name);

            // P2: Handle Range requests
            if let Some(range_val) = &range_header {
                match parse_range_header(range_val, item.size) {
                    Some((start, end)) => {
                        let content_length = end - start + 1;
                        let header = format!(
                            "HTTP/1.1 206 Partial Content\r\n\
                             Content-Type: {}\r\n\
                             Content-Length: {}\r\n\
                             Content-Range: bytes {}-{}/{}\r\n\
                             Accept-Ranges: bytes\r\n\
                             Content-Disposition: attachment; filename=\"{}\"\r\n\
                             Connection: close\r\n\
                             \r\n",
                            mime, content_length, start, end, item.size, safe_name
                        );
                        stream.write_all(header.as_bytes()).await?;

                        f.seek(std::io::SeekFrom::Start(start)).await?;

                        let _transfer_guard = ActiveTransferGuard::new(self.active_transfers.clone());
                        let mut file_buf = [0u8; 65536];
                        let mut remaining = content_length;
                        let mut total_sent: u64 = 0;
                        while remaining > 0 {
                            let to_read = std::cmp::min(remaining as usize, file_buf.len());
                            let n = f.read(&mut file_buf[..to_read]).await?;
                            if n == 0 { break; }
                            stream.write_all(&file_buf[..n]).await?;
                            total_sent += n as u64;
                            remaining -= n as u64;
                            self.bump_activity().await;
                        }
                        stream.flush().await?;
                        let _ = stream.shutdown().await;

                        log::info!(
                            "[nxfr-web] [{}] 206 Partial Content: '{}' bytes {}-{}/{} ({} sent)",
                            ip, item.name, start, end, item.size, total_sent
                        );
                        return Ok(());
                    }
                    None => {
                        // Invalid range
                        let header = format!(
                            "HTTP/1.1 416 Range Not Satisfiable\r\n\
                             Content-Range: bytes */{}\r\n\
                             Connection: close\r\n\
                             \r\n",
                            item.size
                        );
                        stream.write_all(header.as_bytes()).await?;
                        stream.flush().await?;
                        let _ = stream.shutdown().await;
                        return Ok(());
                    }
                }
            }

            // Full file response (200 OK) with Accept-Ranges
            let header = format!(
                "HTTP/1.1 200 OK\r\n\
                 Content-Type: {}\r\n\
                 Content-Length: {}\r\n\
                 Accept-Ranges: bytes\r\n\
                 Content-Disposition: attachment; filename=\"{}\"\r\n\
                 Connection: close\r\n\
                 \r\n",
                mime,
                item.size,
                safe_name
            );

            stream.write_all(header.as_bytes()).await?;

            let _transfer_guard = ActiveTransferGuard::new(self.active_transfers.clone());
            let mut file_buf = [0u8; 65536];
            let mut total_sent: u64 = 0;
            loop {
                let n = f.read(&mut file_buf).await?;
                if n == 0 {
                    break;
                }
                stream.write_all(&file_buf[..n]).await?;
                total_sent += n as u64;
                self.bump_activity().await;
            }
            stream.flush().await?;
            let _ = stream.shutdown().await;

            // P7: Count this download for quota purposes
            let count = self.download_count.fetch_add(1, Ordering::SeqCst) + 1;
            if let Some(max) = self.max_downloads {
                if count as u32 >= max {
                    log::info!("[nxfr-web] Download quota reached ({}/{}), shutting down", count, max);
                    self.cancel.cancel();
                }
            }

            log::info!(
                "[nxfr-web] [{}] 200 OK: Completed streaming '{}' (id={}, {} / {} bytes)",
                ip,
                item.name,
                item.id,
                total_sent,
                item.size
            );
            return Ok(());
        }

        if method == "POST" && path == "/upload" {
            let mut content_length: Option<usize> = None;
            let mut boundary: Option<String> = None;
            let mut auth_token: Option<String> = None;

            for line in lines {
                let lower = line.to_lowercase();
                if lower.starts_with("content-length:") {
                    content_length = line[15..].trim().parse().ok();
                } else if lower.starts_with("content-type:")
                    && lower.contains("multipart/form-data")
                {
                    if let Some(pos) = lower.find("boundary=") {
                        let raw_val = &line[pos + 9..];
                        let mut b_val = raw_val.trim();
                        if let Some(semi) = b_val.find(';') {
                            b_val = &b_val[..semi];
                        }
                        let b_str = b_val
                            .trim()
                            .trim_matches('"')
                            .trim_matches('\'')
                            .to_string();
                        if !b_str.is_empty() {
                            boundary = Some(b_str);
                        }
                    }
                } else if lower.starts_with("authorization: bearer ") {
                    auth_token = Some(line[22..].trim().to_string());
                }
            }

            // Check query string if header missing
            let mut is_query_token = false;
            if auth_token.is_none() && !query.is_empty() {
                for pair in query.split('&') {
                    if let Some((k, v)) = pair.split_once('=') {
                        if k == "t" {
                            auth_token = Some(v.to_string());
                            is_query_token = true;
                        }
                    }
                }
            }

            if is_query_token && auth_token.as_deref() == Some(&self.token) {
                log::debug!("[nxfr-web] token via query string");
            }

            let valid = match &auth_token {
                Some(tok) => tok == &self.token || self.pin.as_ref() == Some(tok),
                None => false,
            };

            if !valid {
                // Record failure
                {
                    let mut failed = self.failed_attempts.lock().await;
                    let entry = failed.entry(ip).or_insert((0, Instant::now()));
                    if entry.1.elapsed() > RATE_LIMIT_WINDOW {
                        entry.0 = 1;
                        entry.1 = Instant::now();
                    } else {
                        entry.0 += 1;
                        entry.1 = Instant::now();
                    }
                }

                log::warn!(
                    "[nxfr-web] [{}] 403 Forbidden: Invalid token for upload",
                    ip
                );
                let body = b"{\"error\": \"Invalid token or PIN\"}";
                return Self::send_response(stream, "403 Forbidden", "application/json", body)
                    .await;
            }

            let boundary_str = match boundary {
                Some(b) => b,
                None => {
                    let body = b"Upload successful";
                    return Self::send_response(stream, "200 OK", "text/plain", body).await;
                }
            };

            if let Some(cl) = content_length {
                if cl as u64 > self.max_file_size {
                    log::warn!(
                        "[nxfr-web] [{}] 413 Payload Too Large: Content-Length {} > {}",
                        ip,
                        cl,
                        self.max_file_size
                    );
                    let body = b"{\"error\": \"File size exceeds limit\"}";
                    return Self::send_response(
                        stream,
                        "413 Payload Too Large",
                        "application/json",
                        body,
                    )
                    .await;
                }
            }

            let mut buffer = headers_buf.split_off(body_start);
            let mut original_filename: Option<String> = None;
            let mut read_buf = [0u8; 65536];

            // 1. Read stream in bounded slices until part headers (\r\n\r\n) are parsed
            loop {
                if let Some(pos) = buffer.windows(4).position(|w| w == b"\r\n\r\n") {
                    let part_header_bytes = &buffer[..pos];
                    if let Ok(header_str) = std::str::from_utf8(part_header_bytes) {
                        for h_line in header_str.lines() {
                            let lower = h_line.to_lowercase();
                            if lower.contains("content-disposition:") {
                                if let Some(fn_idx) = lower.find("filename=\"") {
                                    let rest = &h_line[fn_idx + 10..];
                                    if let Some(end_quote) = rest.find('"') {
                                        let fn_val = rest[..end_quote].trim();
                                        if !fn_val.is_empty() {
                                            original_filename = Some(fn_val.to_string());
                                        }
                                    }
                                } else if let Some(fn_idx) = lower.find("filename=") {
                                    let rest = h_line[fn_idx + 9..].trim().trim_matches('"');
                                    if !rest.is_empty() {
                                        original_filename = Some(rest.to_string());
                                    }
                                }
                            }
                        }
                    }
                    buffer.drain(..pos + 4);
                    break;
                }

                if buffer.len() > 16384 {
                    log::warn!(
                        "[nxfr-web] [{}] 400 Bad Request: Part headers exceeded 16KB",
                        ip
                    );
                    let body = b"{\"error\": \"Malformed multipart headers\"}";
                    return Self::send_response(
                        stream,
                        "400 Bad Request",
                        "application/json",
                        body,
                    )
                    .await;
                }

                let n = stream.read(&mut read_buf).await?;
                if n == 0 {
                    log::warn!(
                        "[nxfr-web] [{}] Client disconnected before part headers completed",
                        ip
                    );
                    let body = b"{\"error\": \"Client disconnected prematurely\"}";
                    return Self::send_response(
                        stream,
                        "400 Bad Request",
                        "application/json",
                        body,
                    )
                    .await;
                }
                buffer.extend_from_slice(&read_buf[..n]);
            }

            // 2. Open destination file (.tmp) BEFORE reading body, and attach RAII cleanup guard
            let inbox_dir = self.receive_dir.join("web-inbox");
            tokio::fs::create_dir_all(&inbox_dir).await?;

            let mut rand_bytes = [0u8; 8];
            getrandom::getrandom(&mut rand_bytes).map_err(|e| format!("getrandom failed: {e}"))?;
            let tmp_filename = format!("web_upload_{}.tmp", hex::encode(rand_bytes));
            let tmp_path = inbox_dir.join(&tmp_filename);

            let mut file = tokio::fs::File::create(&tmp_path).await?;
            let mut guard = TmpFileGuard::new(tmp_path.clone());

            let _transfer_guard = ActiveTransferGuard::new(self.active_transfers.clone());
            let needle = format!("\r\n--{}", boundary_str).into_bytes();
            let needle_len = needle.len();
            let alt_needle = format!("--{}", boundary_str).into_bytes();

            let mut total_written: u64 = 0;
            let mut hasher = Sha256::new();
            let max_allowed = self.max_file_size;

            // 3. Stream body chunks directly to disk in bounded 64KB slices
            loop {
                // Check if closing boundary delimiter is present in current buffer
                if let Some(pos) = buffer
                    .windows(needle_len)
                    .position(|w| w == needle.as_slice())
                {
                    let chunk_to_write = &buffer[..pos];
                    if !chunk_to_write.is_empty() {
                        total_written += chunk_to_write.len() as u64;
                        if total_written > max_allowed {
                            log::warn!(
                                "[nxfr-web] [{}] 413 Payload Too Large: {} > {}",
                                ip,
                                total_written,
                                max_allowed
                            );
                            drop(file);
                            let body = b"{\"error\": \"File size exceeds limit\"}";
                            return Self::send_response(
                                stream,
                                "413 Payload Too Large",
                                "application/json",
                                body,
                            )
                            .await;
                        }
                        file.write_all(chunk_to_write).await?;
                        hasher.update(chunk_to_write);
                        self.bump_activity().await;
                    }
                    break;
                } else if total_written == 0 && buffer.starts_with(&alt_needle) {
                    break;
                }

                // If buffer is larger than delimiter, flush safe prefix directly to disk
                if buffer.len() > needle_len {
                    let safe_len = buffer.len() - needle_len;
                    let chunk_to_write = &buffer[..safe_len];
                    total_written += chunk_to_write.len() as u64;
                    if total_written > max_allowed {
                        log::warn!(
                            "[nxfr-web] [{}] 413 Payload Too Large: {} > {}",
                            ip,
                            total_written,
                            max_allowed
                        );
                        drop(file);
                        let body = b"{\"error\": \"File size exceeds limit\"}";
                        return Self::send_response(
                            stream,
                            "413 Payload Too Large",
                            "application/json",
                            body,
                        )
                        .await;
                    }
                    file.write_all(chunk_to_write).await?;
                    hasher.update(chunk_to_write);
                    self.bump_activity().await;
                    buffer.drain(..safe_len);
                }

                // Read next chunk into stack buffer
                let n = stream.read(&mut read_buf).await?;
                if n == 0 {
                    log::warn!(
                        "[nxfr-web] [{}] Client disconnected prematurely during upload after {} bytes",
                        ip,
                        total_written
                    );
                    drop(file);
                    // guard automatically removes partial .tmp file
                    return Err("Client disconnected during upload".into());
                }
                self.bump_activity().await;
                buffer.extend_from_slice(&read_buf[..n]);
            }

            file.flush().await?;
            drop(file);

            let sha_hex = hex::encode(hasher.finalize());

            // 4. Sanitize filename with subdirectory support (P5: folder uploads)
            let raw_name = original_filename
                .unwrap_or_else(|| format!("upload_{}.bin", hex::encode(&rand_bytes[..4])));

            // Parse relative path: "photos/vacation/img001.jpg" -> subdir + filename
            let (sub_dir, clean_name) = if raw_name.contains('/') {
                // Security: reject absolute paths, .., and excessive nesting
                let components: Vec<&str> = raw_name
                    .split('/')
                    .filter(|c| !c.is_empty() && *c != "." && *c != "..")
                    .collect();
                if components.len() > 11 {
                    log::warn!(
                        "[nxfr-web] [{}] 400 Bad Request: Path too deeply nested ({})",
                        ip,
                        components.len()
                    );
                    let body = b"{\"error\": \"Path too deeply nested (max 10 levels)\"}";
                    return Self::send_response(
                        stream,
                        "400 Bad Request",
                        "application/json",
                        body,
                    )
                    .await;
                }
                if components.is_empty() {
                    (None, sanitize_filename(&raw_name))
                } else if components.len() == 1 {
                    (None, sanitize_filename(components[0]))
                } else {
                    let dir_parts: Vec<String> = components[..components.len() - 1]
                        .iter()
                        .map(|c| sanitize_filename(c))
                        .collect();
                    let file_part = sanitize_filename(components[components.len() - 1]);
                    (Some(dir_parts.join("/")), file_part)
                }
            } else {
                (None, sanitize_filename(&raw_name))
            };

            // Resolve target directory (inbox + optional subdirectory)
            let target_dir = match &sub_dir {
                Some(sd) => {
                    let d = inbox_dir.join(sd);
                    tokio::fs::create_dir_all(&d).await?;
                    d
                }
                None => inbox_dir.clone(),
            };

            let mut final_path = target_dir.join(&clean_name);
            if final_path.exists() {
                let stem = std::path::Path::new(&clean_name)
                    .file_stem()
                    .and_then(|s| s.to_str())
                    .unwrap_or("file");
                let ext = std::path::Path::new(&clean_name)
                    .extension()
                    .and_then(|e| e.to_str())
                    .map(|e| format!(".{}", e))
                    .unwrap_or_default();
                let mut counter = 1;
                let mut candidate = target_dir.join(format!("{} ({}){}", stem, counter, ext));
                while candidate.exists() {
                    counter += 1;
                    candidate = target_dir.join(format!("{} ({}){}", stem, counter, ext));
                }
                final_path = candidate;
            }

            tokio::fs::rename(&tmp_path, &final_path).await?;
            guard.commit(); // Successfully committed, prevent temp cleanup

            log::info!(
                "[nxfr-web] [{}] 200 OK: Web upload successfully streamed to {} ({} bytes, sha256={})",
                ip,
                final_path.display(),
                total_written,
                sha_hex
            );
            let body = b"Upload successful";
            return Self::send_response(stream, "200 OK", "text/plain", body).await;
        }

        log::warn!(
            "[nxfr-web] [{}] 404 Not Found: Unhandled route '{}'",
            ip,
            path
        );
        let body = b"Not Found";
        Self::send_response(stream, "404 Not Found", "text/plain", body).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};
    use std::io::Write;
    use tokio_rustls::TlsConnector;

    #[test]
    fn test_web_share_item_serialization() {
        let item = WebShareItem {
            id: 0,
            name: "test.txt".to_string(),
            size: 100,
            mime: "text/plain".to_string(),
            path: "/tmp/test.txt".to_string(),
        };
        let json = serde_json::to_string(&item).unwrap();
        assert!(json.contains("test.txt"));
        let decoded: WebShareItem = serde_json::from_str(&json).unwrap();
        assert_eq!(decoded.id, 0);
        assert_eq!(decoded.name, "test.txt");
    }

    #[test]
    fn test_sanitize_filename() {
        assert_eq!(sanitize_filename("photo 1.jpg"), "photo_1.jpg");
        assert_eq!(sanitize_filename("data/file.txt"), "data_file.txt");

        // Path traversal dot tests
        let empty_res = sanitize_filename("");
        assert!(empty_res.starts_with("uploaded_file_") && empty_res.ends_with(".bin"));

        let dot_res = sanitize_filename(".");
        assert!(dot_res.starts_with("uploaded_file_") && dot_res.ends_with(".bin"));

        let dotdot_res = sanitize_filename("..");
        assert!(dotdot_res.starts_with("uploaded_file_") && dotdot_res.ends_with(".bin"));

        let dots_res = sanitize_filename("...");
        assert!(dots_res.starts_with("uploaded_file_") && dots_res.ends_with(".bin"));
    }

    #[tokio::test]
    async fn test_concurrent_share_downloads_with_sha256() {
        let temp_dir = std::env::temp_dir().join(format!("nxfr_test_share_{}", generate_token()));
        std::fs::create_dir_all(&temp_dir).unwrap();

        let identity = nxfr_crypto::identity::generate_identity().unwrap();

        // Create 2 test files with distinct content
        let file1_path = temp_dir.join("payload_alpha.bin");
        let file2_path = temp_dir.join("payload_beta.bin");

        let content1 = b"ALPHA_STREAM_PACKET_CONTENT_1234567890";
        let content2 = b"BETA_STREAM_PACKET_CONTENT_0987654321_ABCDEFGHIJ";

        let mut f1 = std::fs::File::create(&file1_path).unwrap();
        f1.write_all(content1).unwrap();
        drop(f1);

        let mut f2 = std::fs::File::create(&file2_path).unwrap();
        f2.write_all(content2).unwrap();
        drop(f2);

        let hash1_expected = hex::encode(Sha256::digest(content1));
        let hash2_expected = hex::encode(Sha256::digest(content2));

        let manifest = vec![
            WebShareItem {
                id: 0,
                name: "payload_alpha.bin".to_string(),
                size: content1.len() as u64,
                mime: "application/octet-stream".to_string(),
                path: file1_path.to_str().unwrap().to_string(),
            },
            WebShareItem {
                id: 1,
                name: "payload_beta.bin".to_string(),
                size: content2.len() as u64,
                mime: "application/octet-stream".to_string(),
                path: file2_path.to_str().unwrap().to_string(),
            },
        ];

        let handle = WebServer::start_share(
            &identity.private_key_der,
            &identity.cert_der,
            17450,
            None,
            manifest,
        )
        .await
        .expect("Failed to start share server");

        let server_port = handle.port;
        let token = handle.token.clone();

        // Setup TLS client config that accepts the self-signed cert
        #[derive(Debug)]
        struct NoCertVerifier;
        impl rustls::client::danger::ServerCertVerifier for NoCertVerifier {
            fn verify_server_cert(
                &self,
                _end_entity: &rustls_pki_types::CertificateDer<'_>,
                _intermediates: &[rustls_pki_types::CertificateDer<'_>],
                _server_name: &rustls_pki_types::ServerName<'_>,
                _ocsp_response: &[u8],
                _now: rustls_pki_types::UnixTime,
            ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
                Ok(rustls::client::danger::ServerCertVerified::assertion())
            }
            fn verify_tls12_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn verify_tls13_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
                vec![
                    rustls::SignatureScheme::ED25519,
                    rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
                    rustls::SignatureScheme::RSA_PSS_SHA256,
                ]
            }
        }

        let mut client_config = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(NoCertVerifier))
            .with_no_client_auth();
        client_config.alpn_protocols = vec![b"http/1.1".to_vec()];
        let connector = TlsConnector::from(Arc::new(client_config));

        // Client 1: Download item 0 via Authorization: Bearer <token>
        let connector1 = connector.clone();
        let token1 = token.clone();
        let task1 = tokio::spawn(async move {
            let stream = TcpStream::connect(("127.0.0.1", server_port))
                .await
                .unwrap();
            let domain = rustls_pki_types::ServerName::try_from("localhost".to_string()).unwrap();
            let mut tls_stream = connector1.connect(domain, stream).await.unwrap();

            let request = format!(
                "GET /dl/0 HTTP/1.1\r\n\
                 Host: localhost:{}\r\n\
                 Authorization: Bearer {}\r\n\
                 Connection: close\r\n\
                 \r\n",
                server_port, token1
            );
            tls_stream.write_all(request.as_bytes()).await.unwrap();
            tls_stream.flush().await.unwrap();

            let mut resp = Vec::new();
            tls_stream.read_to_end(&mut resp).await.unwrap();
            resp
        });

        // Client 2: Download item 1 via query string ?t=<token>
        let connector2 = connector.clone();
        let token2 = token.clone();
        let task2 = tokio::spawn(async move {
            let stream = TcpStream::connect(("127.0.0.1", server_port))
                .await
                .unwrap();
            let domain = rustls_pki_types::ServerName::try_from("localhost".to_string()).unwrap();
            let mut tls_stream = connector2.connect(domain, stream).await.unwrap();

            let request = format!(
                "GET /dl/1?t={} HTTP/1.1\r\n\
                 Host: localhost:{}\r\n\
                 Connection: close\r\n\
                 \r\n",
                token2, server_port
            );
            tls_stream.write_all(request.as_bytes()).await.unwrap();
            tls_stream.flush().await.unwrap();

            let mut resp = Vec::new();
            tls_stream.read_to_end(&mut resp).await.unwrap();
            resp
        });

        let (resp1, resp2) = tokio::join!(task1, task2);
        let resp1 = resp1.unwrap();
        let resp2 = resp2.unwrap();

        // Extract body after \r\n\r\n
        let body1_pos = resp1.windows(4).position(|w| w == b"\r\n\r\n").unwrap() + 4;
        let body1 = &resp1[body1_pos..];
        let hash1_actual = hex::encode(Sha256::digest(body1));
        assert_eq!(hash1_actual, hash1_expected);
        assert_eq!(body1, content1);

        let body2_pos = resp2.windows(4).position(|w| w == b"\r\n\r\n").unwrap() + 4;
        let body2 = &resp2[body2_pos..];
        let hash2_actual = hex::encode(Sha256::digest(body2));
        assert_eq!(hash2_actual, hash2_expected);
        assert_eq!(body2, content2);

        handle.stop();
        let _ = std::fs::remove_dir_all(&temp_dir);
    }

    #[tokio::test]
    async fn test_web_upload_saves_original_filename() {
        let temp_dir =
            std::env::temp_dir().join(format!("nxfr_web_upload_test_{}", std::process::id()));
        std::fs::create_dir_all(&temp_dir).unwrap();

        let identity = nxfr_crypto::identity::generate_identity().unwrap();
        let handle = WebServer::start(
            &identity.private_key_der,
            &identity.cert_der,
            temp_dir.clone(),
            17460,
            None,
        )
        .await
        .unwrap();

        let server_port = handle.port;
        let token = handle.token.clone();

        #[derive(Debug)]
        struct NoCertVerifier;
        impl rustls::client::danger::ServerCertVerifier for NoCertVerifier {
            fn verify_server_cert(
                &self,
                _end_entity: &rustls_pki_types::CertificateDer<'_>,
                _intermediates: &[rustls_pki_types::CertificateDer<'_>],
                _server_name: &rustls_pki_types::ServerName<'_>,
                _ocsp_response: &[u8],
                _now: rustls_pki_types::UnixTime,
            ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
                Ok(rustls::client::danger::ServerCertVerified::assertion())
            }
            fn verify_tls12_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn verify_tls13_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
                vec![
                    rustls::SignatureScheme::ED25519,
                    rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
                    rustls::SignatureScheme::RSA_PSS_SHA256,
                ]
            }
        }

        let mut client_config = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(NoCertVerifier))
            .with_no_client_auth();
        client_config.alpn_protocols = vec![b"http/1.1".to_vec()];
        let connector = TlsConnector::from(Arc::new(client_config));

        let stream = TcpStream::connect(("127.0.0.1", server_port))
            .await
            .unwrap();
        let domain = rustls_pki_types::ServerName::try_from("localhost".to_string()).unwrap();
        let mut tls_stream = connector.connect(domain, stream).await.unwrap();

        let boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        let file_content = b"Hello, NXFR Web Upload test payload!";
        let mut body = Vec::new();
        body.extend_from_slice(format!("--{}\r\n", boundary).as_bytes());
        body.extend_from_slice(
            b"Content-Disposition: form-data; name=\"file\"; filename=\"vacation_photo.jpg\"\r\n",
        );
        body.extend_from_slice(b"Content-Type: image/jpeg\r\n\r\n");
        body.extend_from_slice(file_content);
        body.extend_from_slice(format!("\r\n--{}--\r\n", boundary).as_bytes());

        let request = format!(
            "POST /upload HTTP/1.1\r\n\
             Host: localhost:{}\r\n\
             Authorization: Bearer {}\r\n\
             Content-Type: multipart/form-data; boundary={}\r\n\
             Content-Length: {}\r\n\
             Connection: close\r\n\
             \r\n",
            server_port,
            token,
            boundary,
            body.len()
        );

        tls_stream.write_all(request.as_bytes()).await.unwrap();
        tls_stream.write_all(&body).await.unwrap();
        tls_stream.flush().await.unwrap();

        let mut resp = Vec::new();
        tls_stream.read_to_end(&mut resp).await.unwrap();
        let resp_str = String::from_utf8_lossy(&resp);
        assert!(resp_str.starts_with("HTTP/1.1 200 OK"));

        let saved_file = temp_dir.join("web-inbox").join("vacation_photo.jpg");
        assert!(
            saved_file.exists(),
            "Uploaded file vacation_photo.jpg should exist in web-inbox"
        );
        let saved_content = std::fs::read(&saved_file).unwrap();
        assert_eq!(saved_content, file_content);

        handle.stop();
        let _ = std::fs::remove_dir_all(&temp_dir);
    }

    #[tokio::test]
    async fn test_web_share_pin_authentication_flow() {
        let temp_dir = std::env::temp_dir().join(format!("nxfr_pin_test_{}", std::process::id()));
        std::fs::create_dir_all(&temp_dir).unwrap();

        let identity = nxfr_crypto::identity::generate_identity().unwrap();
        let test_file = temp_dir.join("secret_doc.pdf");
        std::fs::write(&test_file, b"%PDF-1.4 SECRET PAYLOAD").unwrap();

        let manifest = vec![WebShareItem {
            id: 0,
            name: "secret_doc.pdf".to_string(),
            size: 23,
            mime: "application/pdf".to_string(),
            path: test_file.to_str().unwrap().to_string(),
        }];

        let pin = "8492".to_string();
        let handle = WebServer::start_share(
            &identity.private_key_der,
            &identity.cert_der,
            17470,
            Some(pin.clone()),
            manifest,
        )
        .await
        .unwrap();

        let server_port = handle.port;

        #[derive(Debug)]
        struct NoCertVerifier;
        impl rustls::client::danger::ServerCertVerifier for NoCertVerifier {
            fn verify_server_cert(
                &self,
                _end_entity: &rustls_pki_types::CertificateDer<'_>,
                _intermediates: &[rustls_pki_types::CertificateDer<'_>],
                _server_name: &rustls_pki_types::ServerName<'_>,
                _ocsp_response: &[u8],
                _now: rustls_pki_types::UnixTime,
            ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
                Ok(rustls::client::danger::ServerCertVerified::assertion())
            }
            fn verify_tls12_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn verify_tls13_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
                vec![
                    rustls::SignatureScheme::ED25519,
                    rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
                    rustls::SignatureScheme::RSA_PSS_SHA256,
                ]
            }
        }

        let mut client_config = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(NoCertVerifier))
            .with_no_client_auth();
        client_config.alpn_protocols = vec![b"http/1.1".to_vec()];
        let connector = TlsConnector::from(Arc::new(client_config));

        // 1. Test wrong PIN -> 403
        let stream = TcpStream::connect(("127.0.0.1", server_port))
            .await
            .unwrap();
        let domain = rustls_pki_types::ServerName::try_from("localhost".to_string()).unwrap();
        let mut tls = connector.connect(domain.clone(), stream).await.unwrap();
        let req_wrong = format!(
            "GET /auth HTTP/1.1\r\nHost: localhost:{}\r\nAuthorization: Bearer 0000\r\nConnection: close\r\n\r\n",
            server_port
        );
        tls.write_all(req_wrong.as_bytes()).await.unwrap();
        let mut resp = Vec::new();
        tls.read_to_end(&mut resp).await.unwrap();
        assert!(String::from_utf8_lossy(&resp).starts_with("HTTP/1.1 403"));

        // 2. Test correct PIN -> 200
        let stream = TcpStream::connect(("127.0.0.1", server_port))
            .await
            .unwrap();
        let mut tls = connector.connect(domain.clone(), stream).await.unwrap();
        let req_correct = format!(
            "GET /auth HTTP/1.1\r\nHost: localhost:{}\r\nAuthorization: Bearer {}\r\nConnection: close\r\n\r\n",
            server_port, pin
        );
        tls.write_all(req_correct.as_bytes()).await.unwrap();
        let mut resp = Vec::new();
        tls.read_to_end(&mut resp).await.unwrap();
        assert!(String::from_utf8_lossy(&resp).starts_with("HTTP/1.1 200"));

        // 3. Test download with correct PIN -> 200
        let stream = TcpStream::connect(("127.0.0.1", server_port))
            .await
            .unwrap();
        let mut tls = connector.connect(domain, stream).await.unwrap();
        let req_dl = format!(
            "GET /dl/0 HTTP/1.1\r\nHost: localhost:{}\r\nAuthorization: Bearer {}\r\nConnection: close\r\n\r\n",
            server_port, pin
        );
        tls.write_all(req_dl.as_bytes()).await.unwrap();
        let mut resp = Vec::new();
        tls.read_to_end(&mut resp).await.unwrap();
        assert!(String::from_utf8_lossy(&resp).starts_with("HTTP/1.1 200 OK"));
        assert!(String::from_utf8_lossy(&resp).contains("%PDF-1.4 SECRET PAYLOAD"));

        handle.stop();
        let _ = std::fs::remove_dir_all(&temp_dir);
    }

    #[tokio::test]
    async fn test_large_chunked_streaming_upload_with_sha256() {
        let temp_dir =
            std::env::temp_dir().join(format!("nxfr_large_stream_test_{}", std::process::id()));
        std::fs::create_dir_all(&temp_dir).unwrap();

        let identity = nxfr_crypto::identity::generate_identity().unwrap();
        let handle = WebServer::start(
            &identity.private_key_der,
            &identity.cert_der,
            temp_dir.clone(),
            17480,
            None,
        )
        .await
        .unwrap();

        let server_port = handle.port;
        let token = handle.token.clone();

        #[derive(Debug)]
        struct NoCertVerifier;
        impl rustls::client::danger::ServerCertVerifier for NoCertVerifier {
            fn verify_server_cert(
                &self,
                _end_entity: &rustls_pki_types::CertificateDer<'_>,
                _intermediates: &[rustls_pki_types::CertificateDer<'_>],
                _server_name: &rustls_pki_types::ServerName<'_>,
                _ocsp_response: &[u8],
                _now: rustls_pki_types::UnixTime,
            ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
                Ok(rustls::client::danger::ServerCertVerified::assertion())
            }
            fn verify_tls12_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn verify_tls13_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
                vec![
                    rustls::SignatureScheme::ED25519,
                    rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
                    rustls::SignatureScheme::RSA_PSS_SHA256,
                ]
            }
        }

        let mut client_config = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(NoCertVerifier))
            .with_no_client_auth();
        client_config.alpn_protocols = vec![b"http/1.1".to_vec()];
        let connector = TlsConnector::from(Arc::new(client_config));

        let stream = TcpStream::connect(("127.0.0.1", server_port))
            .await
            .unwrap();
        let domain = rustls_pki_types::ServerName::try_from("localhost".to_string()).unwrap();
        let mut tls_stream = connector.connect(domain, stream).await.unwrap();

        let boundary = "----WebKitFormBoundarySTREAMTEST0123456";
        let part_header = format!(
            "--{}\r\n\
             Content-Disposition: form-data; name=\"file\"; filename=\"large_blob.bin\"\r\n\
             Content-Type: application/octet-stream\r\n\
             \r\n",
            boundary
        );
        let part_footer = format!("\r\n--{}--\r\n", boundary);

        // Generate 2MB pseudo-random deterministic test payload
        let total_payload_size: usize = 2 * 1024 * 1024;
        let mut test_payload = Vec::with_capacity(total_payload_size);
        for i in 0..total_payload_size {
            test_payload.push((i % 251) as u8);
        }

        let expected_sha256 = hex::encode(Sha256::digest(&test_payload));
        let total_body_len = part_header.len() + test_payload.len() + part_footer.len();

        let request = format!(
            "POST /upload HTTP/1.1\r\n\
             Host: localhost:{}\r\n\
             Authorization: Bearer {}\r\n\
             Content-Type: multipart/form-data; boundary={}\r\n\
             Content-Length: {}\r\n\
             Connection: close\r\n\
             \r\n",
            server_port, token, boundary, total_body_len
        );

        tls_stream.write_all(request.as_bytes()).await.unwrap();
        tls_stream.write_all(part_header.as_bytes()).await.unwrap();

        // Stream payload in 64KB slices
        let chunk_size = 65536;
        for chunk in test_payload.chunks(chunk_size) {
            tls_stream.write_all(chunk).await.unwrap();
            tokio::task::yield_now().await;
        }

        tls_stream.write_all(part_footer.as_bytes()).await.unwrap();
        tls_stream.flush().await.unwrap();

        let mut resp = Vec::new();
        tls_stream.read_to_end(&mut resp).await.unwrap();
        let resp_str = String::from_utf8_lossy(&resp);
        assert!(
            resp_str.starts_with("HTTP/1.1 200 OK"),
            "Expected 200 OK, got: {}",
            resp_str
        );

        let saved_file = temp_dir.join("web-inbox").join("large_blob.bin");
        assert!(
            saved_file.exists(),
            "Streamed file large_blob.bin must exist in web-inbox"
        );

        let saved_content = std::fs::read(&saved_file).unwrap();
        assert_eq!(saved_content.len(), total_payload_size);
        let actual_sha256 = hex::encode(Sha256::digest(&saved_content));
        assert_eq!(actual_sha256, expected_sha256);

        // Verify no leftover .tmp files
        let inbox_entries = std::fs::read_dir(temp_dir.join("web-inbox")).unwrap();
        for entry in inbox_entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            assert!(
                !name.ends_with(".tmp"),
                "Leftover tmp file detected: {}",
                name
            );
        }

        handle.stop();
        let _ = std::fs::remove_dir_all(&temp_dir);
    }

    #[tokio::test]
    async fn test_upload_disconnect_cleans_up_temp_file() {
        let temp_dir =
            std::env::temp_dir().join(format!("nxfr_disconnect_test_{}", std::process::id()));
        std::fs::create_dir_all(&temp_dir).unwrap();

        let identity = nxfr_crypto::identity::generate_identity().unwrap();
        let handle = WebServer::start(
            &identity.private_key_der,
            &identity.cert_der,
            temp_dir.clone(),
            17490,
            None,
        )
        .await
        .unwrap();

        let server_port = handle.port;
        let token = handle.token.clone();

        #[derive(Debug)]
        struct NoCertVerifier;
        impl rustls::client::danger::ServerCertVerifier for NoCertVerifier {
            fn verify_server_cert(
                &self,
                _end_entity: &rustls_pki_types::CertificateDer<'_>,
                _intermediates: &[rustls_pki_types::CertificateDer<'_>],
                _server_name: &rustls_pki_types::ServerName<'_>,
                _ocsp_response: &[u8],
                _now: rustls_pki_types::UnixTime,
            ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
                Ok(rustls::client::danger::ServerCertVerified::assertion())
            }
            fn verify_tls12_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn verify_tls13_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
                vec![
                    rustls::SignatureScheme::ED25519,
                    rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
                    rustls::SignatureScheme::RSA_PSS_SHA256,
                ]
            }
        }

        let mut client_config = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(NoCertVerifier))
            .with_no_client_auth();
        client_config.alpn_protocols = vec![b"http/1.1".to_vec()];
        let connector = TlsConnector::from(Arc::new(client_config));

        let stream = TcpStream::connect(("127.0.0.1", server_port))
            .await
            .unwrap();
        let domain = rustls_pki_types::ServerName::try_from("localhost".to_string()).unwrap();
        let mut tls_stream = connector.connect(domain, stream).await.unwrap();

        let boundary = "----WebKitFormBoundaryDISCONNECTTEST";
        let part_header = format!(
            "--{}\r\n\
             Content-Disposition: form-data; name=\"file\"; filename=\"aborted.bin\"\r\n\
             Content-Type: application/octet-stream\r\n\
             \r\n",
            boundary
        );

        let request = format!(
            "POST /upload HTTP/1.1\r\n\
             Host: localhost:{}\r\n\
             Authorization: Bearer {}\r\n\
             Content-Type: multipart/form-data; boundary={}\r\n\
             Content-Length: 10485760\r\n\
             Connection: close\r\n\
             \r\n",
            server_port, token, boundary
        );

        tls_stream.write_all(request.as_bytes()).await.unwrap();
        tls_stream.write_all(part_header.as_bytes()).await.unwrap();
        tls_stream
            .write_all(b"PARTIAL_CHUNK_DATA_BEFORE_DROP")
            .await
            .unwrap();
        tls_stream.flush().await.unwrap();

        // Prematurely drop connection
        drop(tls_stream);

        // Allow server handler to notice EOF and run cleanup
        tokio::time::sleep(std::time::Duration::from_millis(150)).await;

        // Verify web-inbox contains NO .tmp files and NO aborted.bin
        let inbox = temp_dir.join("web-inbox");
        if inbox.exists() {
            let entries: Vec<_> = std::fs::read_dir(&inbox).unwrap().flatten().collect();
            for entry in entries {
                let name = entry.file_name().to_string_lossy().to_string();
                assert!(
                    !name.ends_with(".tmp"),
                    "Tmp file should be deleted on disconnect: {}",
                    name
                );
                assert_ne!(
                    name, "aborted.bin",
                    "Incomplete upload should not be committed"
                );
            }
        }

        handle.stop();
        let _ = std::fs::remove_dir_all(&temp_dir);
    }

    #[tokio::test]
    async fn test_upload_size_limit_exceeded_cleans_up_temp_file() {
        let temp_dir =
            std::env::temp_dir().join(format!("nxfr_size_limit_test_{}", std::process::id()));
        std::fs::create_dir_all(&temp_dir).unwrap();

        let identity = nxfr_crypto::identity::generate_identity().unwrap();

        // Start server with a small 500-byte max_file_size limit
        let fp_bytes =
            nxfr_crypto::identity::device_id_from_cert(&identity.cert_der).unwrap_or([0u8; 32]);
        let fp_formatted = fp_bytes
            .iter()
            .map(|b| format!("{:02X}", b))
            .collect::<Vec<_>>()
            .join(":");

        let (server, cancel) = WebServer::new(
            temp_dir.clone(),
            17495,
            500, // 500 byte limit
            None,
            fp_formatted,
            None,
        );
        let token = server.token.clone();

        let tls_config =
            build_web_tls_config(&identity.private_key_der, &identity.cert_der).unwrap();
        let acceptor = TlsAcceptor::from(tls_config);
        let listener = TcpListener::bind(("127.0.0.1", 17495)).await.unwrap();
        let server_arc = Arc::new(server);

        let cancel_clone = cancel.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = cancel_clone.cancelled() => break,
                    res = listener.accept() => {
                        if let Ok((stream, addr)) = res {
                            let acceptor = acceptor.clone();
                            let server_arc = server_arc.clone();
                            tokio::spawn(async move {
                                if let Ok(mut tls) = acceptor.accept(stream).await {
                                    let _ = server_arc.handle_connection(&mut tls, addr.ip()).await;
                                }
                            });
                        }
                    }
                }
            }
        });

        #[derive(Debug)]
        struct NoCertVerifier;
        impl rustls::client::danger::ServerCertVerifier for NoCertVerifier {
            fn verify_server_cert(
                &self,
                _end_entity: &rustls_pki_types::CertificateDer<'_>,
                _intermediates: &[rustls_pki_types::CertificateDer<'_>],
                _server_name: &rustls_pki_types::ServerName<'_>,
                _ocsp_response: &[u8],
                _now: rustls_pki_types::UnixTime,
            ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
                Ok(rustls::client::danger::ServerCertVerified::assertion())
            }
            fn verify_tls12_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn verify_tls13_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
                vec![
                    rustls::SignatureScheme::ED25519,
                    rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
                    rustls::SignatureScheme::RSA_PSS_SHA256,
                ]
            }
        }

        let mut client_config = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(NoCertVerifier))
            .with_no_client_auth();
        client_config.alpn_protocols = vec![b"http/1.1".to_vec()];
        let connector = TlsConnector::from(Arc::new(client_config));

        let stream = TcpStream::connect(("127.0.0.1", 17495)).await.unwrap();
        let domain = rustls_pki_types::ServerName::try_from("localhost".to_string()).unwrap();
        let mut tls_stream = connector.connect(domain, stream).await.unwrap();

        let boundary = "----WebKitFormBoundarySIZELIMIT";
        let part_header = format!(
            "--{}\r\n\
             Content-Disposition: form-data; name=\"file\"; filename=\"too_large.bin\"\r\n\
             Content-Type: application/octet-stream\r\n\
             \r\n",
            boundary
        );
        let part_footer = format!("\r\n--{}--\r\n", boundary);
        let oversize_payload = vec![0x42u8; 1000]; // 1000 bytes > 500 limit

        let total_body_len = part_header.len() + oversize_payload.len() + part_footer.len();
        let request = format!(
            "POST /upload HTTP/1.1\r\n\
             Host: localhost:17495\r\n\
             Authorization: Bearer {}\r\n\
             Content-Type: multipart/form-data; boundary={}\r\n\
             Content-Length: {}\r\n\
             Connection: close\r\n\
             \r\n",
            token, boundary, total_body_len
        );

        tls_stream.write_all(request.as_bytes()).await.unwrap();
        tls_stream.write_all(part_header.as_bytes()).await.unwrap();
        tls_stream.write_all(&oversize_payload).await.unwrap();
        tls_stream.write_all(part_footer.as_bytes()).await.unwrap();
        tls_stream.flush().await.unwrap();

        let mut resp = Vec::new();
        tls_stream.read_to_end(&mut resp).await.unwrap();
        let resp_str = String::from_utf8_lossy(&resp);
        assert!(
            resp_str.starts_with("HTTP/1.1 413 Payload Too Large"),
            "Expected 413, got: {}",
            resp_str
        );

        // Verify web-inbox has NO .tmp files and NO too_large.bin
        let inbox = temp_dir.join("web-inbox");
        if inbox.exists() {
            let entries: Vec<_> = std::fs::read_dir(&inbox).unwrap().flatten().collect();
            for entry in entries {
                let name = entry.file_name().to_string_lossy().to_string();
                assert!(
                    !name.ends_with(".tmp"),
                    "Tmp file should be deleted on size limit rejection: {}",
                    name
                );
                assert_ne!(name, "too_large.bin", "Oversize file must not be committed");
            }
        }

        cancel.cancel();
        let _ = std::fs::remove_dir_all(&temp_dir);
    }

    #[tokio::test]
    #[ignore = "800MB stress test takes ~60s in debug mode"]
    async fn test_streaming_800mb_upload_memory_and_sha256() {
        let temp_dir =
            std::env::temp_dir().join(format!("nxfr_800mb_stream_test_{}", std::process::id()));
        std::fs::create_dir_all(&temp_dir).unwrap();

        let identity = nxfr_crypto::identity::generate_identity().unwrap();
        let handle = WebServer::start(
            &identity.private_key_der,
            &identity.cert_der,
            temp_dir.clone(),
            17510,
            None,
        )
        .await
        .unwrap();

        let server_port = handle.port;
        let token = handle.token.clone();

        #[derive(Debug)]
        struct NoCertVerifier;
        impl rustls::client::danger::ServerCertVerifier for NoCertVerifier {
            fn verify_server_cert(
                &self,
                _end_entity: &rustls_pki_types::CertificateDer<'_>,
                _intermediates: &[rustls_pki_types::CertificateDer<'_>],
                _server_name: &rustls_pki_types::ServerName<'_>,
                _ocsp_response: &[u8],
                _now: rustls_pki_types::UnixTime,
            ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
                Ok(rustls::client::danger::ServerCertVerified::assertion())
            }
            fn verify_tls12_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn verify_tls13_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
                vec![
                    rustls::SignatureScheme::ED25519,
                    rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
                    rustls::SignatureScheme::RSA_PSS_SHA256,
                ]
            }
        }

        let mut client_config = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(NoCertVerifier))
            .with_no_client_auth();
        client_config.alpn_protocols = vec![b"http/1.1".to_vec()];
        let connector = TlsConnector::from(Arc::new(client_config));

        let stream = TcpStream::connect(("127.0.0.1", server_port))
            .await
            .unwrap();
        let domain = rustls_pki_types::ServerName::try_from("localhost".to_string()).unwrap();
        let mut tls_stream = connector.connect(domain, stream).await.unwrap();

        let boundary = "----WebKitFormBoundary800MBUPLOADTEST";
        let part_header = format!(
            "--{}\r\n\
             Content-Disposition: form-data; name=\"file\"; filename=\"video_800mb.iso\"\r\n\
             Content-Type: application/octet-stream\r\n\
             \r\n",
            boundary
        );
        let part_footer = format!("\r\n--{}--\r\n", boundary);

        // 800 MB = 800 * 1024 * 1024 = 838,860,800 bytes
        let total_file_size: u64 = 800 * 1024 * 1024;
        let total_body_len = part_header.len() as u64 + total_file_size + part_footer.len() as u64;

        let request = format!(
            "POST /upload HTTP/1.1\r\n\
             Host: localhost:{}\r\n\
             Authorization: Bearer {}\r\n\
             Content-Type: multipart/form-data; boundary={}\r\n\
             Content-Length: {}\r\n\
             Connection: close\r\n\
             \r\n",
            server_port, token, boundary, total_body_len
        );

        tls_stream.write_all(request.as_bytes()).await.unwrap();
        tls_stream.write_all(part_header.as_bytes()).await.unwrap();

        // Stream 800 MB in repeating 128KB pattern chunks while computing expected SHA-256
        let chunk_size = 131072; // 128 KB
        let mut pattern_chunk = vec![0u8; chunk_size];
        for (i, b) in pattern_chunk.iter_mut().enumerate() {
            *b = ((i * 31 + 17) % 251) as u8;
        }

        let mut expected_hasher = Sha256::new();
        let mut bytes_sent: u64 = 0;
        while bytes_sent < total_file_size {
            let to_send = std::cmp::min(chunk_size as u64, total_file_size - bytes_sent) as usize;
            let slice = &pattern_chunk[..to_send];
            expected_hasher.update(slice);
            tls_stream.write_all(slice).await.unwrap();
            bytes_sent += to_send as u64;
        }

        tls_stream.write_all(part_footer.as_bytes()).await.unwrap();
        tls_stream.flush().await.unwrap();

        let expected_sha256 = hex::encode(expected_hasher.finalize());

        let mut resp = Vec::new();
        tls_stream.read_to_end(&mut resp).await.unwrap();
        let resp_str = String::from_utf8_lossy(&resp);
        assert!(
            resp_str.starts_with("HTTP/1.1 200 OK"),
            "Expected 200 OK, got: {}",
            resp_str
        );

        let saved_file = temp_dir.join("web-inbox").join("video_800mb.iso");
        assert!(
            saved_file.exists(),
            "Streamed file video_800mb.iso must exist in web-inbox"
        );

        let metadata = std::fs::metadata(&saved_file).unwrap();
        assert_eq!(
            metadata.len(),
            total_file_size,
            "Saved file size must match 800MB exactly"
        );

        // Verify SHA-256 of the 800MB file on disk
        let mut disk_file = std::fs::File::open(&saved_file).unwrap();
        let mut disk_hasher = Sha256::new();
        let mut read_buf = vec![0u8; 131072];
        use std::io::Read;
        loop {
            let n = disk_file.read(&mut read_buf).unwrap();
            if n == 0 {
                break;
            }
            disk_hasher.update(&read_buf[..n]);
        }
        let disk_sha256 = hex::encode(disk_hasher.finalize());
        assert_eq!(disk_sha256, expected_sha256);

        handle.stop();
        let _ = std::fs::remove_dir_all(&temp_dir);
    }

    #[tokio::test]
    async fn test_web_share_idle_expiry_and_active_transfer_deferral() {
        let temp_dir =
            std::env::temp_dir().join(format!("nxfr_expiry_test_{}", std::process::id()));
        std::fs::create_dir_all(&temp_dir).unwrap();

        let file_path = temp_dir.join("sample.bin");
        let file_size: usize = 1024 * 1024; // 1 MB
        let mut test_data = vec![0u8; file_size];
        for (i, b) in test_data.iter_mut().enumerate() {
            *b = (i % 256) as u8;
        }
        std::fs::write(&file_path, &test_data).unwrap();
        let expected_sha256 = hex::encode(Sha256::digest(&test_data));

        let manifest = vec![WebShareItem {
            id: 0,
            name: "sample.bin".to_string(),
            size: file_size as u64,
            mime: "application/octet-stream".to_string(),
            path: file_path.to_string_lossy().to_string(),
        }];

        let identity = nxfr_crypto::identity::generate_identity().unwrap();

        // Start share server with a strict 2-second idle expiry
        let expiry_duration = Duration::from_secs(2);
        let handle = WebServer::start_share_with_expiry(
            &identity.private_key_der,
            &identity.cert_der,
            17520,
            None,
            manifest,
            expiry_duration,
        )
        .await
        .unwrap();

        let server_port = handle.port;
        let token = handle.token.clone();

        #[derive(Debug)]
        struct NoCertVerifier;
        impl rustls::client::danger::ServerCertVerifier for NoCertVerifier {
            fn verify_server_cert(
                &self,
                _end_entity: &rustls_pki_types::CertificateDer<'_>,
                _intermediates: &[rustls_pki_types::CertificateDer<'_>],
                _server_name: &rustls_pki_types::ServerName<'_>,
                _ocsp_response: &[u8],
                _now: rustls_pki_types::UnixTime,
            ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
                Ok(rustls::client::danger::ServerCertVerified::assertion())
            }
            fn verify_tls12_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn verify_tls13_signature(
                &self,
                _message: &[u8],
                _cert: &rustls_pki_types::CertificateDer<'_>,
                _dss: &rustls::DigitallySignedStruct,
            ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error>
            {
                Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
            }
            fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
                vec![
                    rustls::SignatureScheme::ED25519,
                    rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
                    rustls::SignatureScheme::RSA_PSS_SHA256,
                ]
            }
        }

        let mut client_config = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(NoCertVerifier))
            .with_no_client_auth();
        client_config.alpn_protocols = vec![b"http/1.1".to_vec()];
        let connector = tokio_rustls::TlsConnector::from(Arc::new(client_config));

        // Connect and initiate a throttled stream that takes ~3.5s (surpassing the 2s expiry)
        let stream = TcpStream::connect(("127.0.0.1", server_port))
            .await
            .unwrap();
        let domain = rustls_pki_types::ServerName::try_from("localhost")
            .unwrap()
            .to_owned();
        let mut tls_stream = connector.connect(domain, stream).await.unwrap();

        let request = format!(
            "GET /dl/0 HTTP/1.1\r\n\
             Host: localhost:{}\r\n\
             Authorization: Bearer {}\r\n\
             Connection: close\r\n\
             \r\n",
            server_port, token
        );
        tls_stream.write_all(request.as_bytes()).await.unwrap();
        tls_stream.flush().await.unwrap();

        let mut raw_resp = Vec::new();
        let mut buf = [0u8; 16384];

        // Read in small delayed chunks across ~3.5 seconds
        while raw_resp.len() < file_size {
            let n = tls_stream.read(&mut buf).await.unwrap();
            if n == 0 {
                break;
            }
            raw_resp.extend_from_slice(&buf[..n]);
            tokio::time::sleep(Duration::from_millis(50)).await;
        }

        // Read remaining bytes
        let _ = tls_stream.read_to_end(&mut raw_resp).await;

        // Locate HTTP header end (\r\n\r\n)
        let header_end = raw_resp
            .windows(4)
            .position(|w| w == b"\r\n\r\n")
            .expect("Must receive valid HTTP response headers");
        let body = &raw_resp[header_end + 4..];

        assert_eq!(
            body.len(),
            file_size,
            "Body length must match file size despite 2s server expiry"
        );
        let actual_sha256 = hex::encode(Sha256::digest(body));
        assert_eq!(
            actual_sha256, expected_sha256,
            "SHA256 must match original data"
        );

        drop(tls_stream);

        // Wait 3 seconds of silence (no requests, 0 active transfers)
        tokio::time::sleep(Duration::from_millis(3000)).await;

        // Assert port is closed and new connections are refused
        let connect_attempt = TcpStream::connect(("127.0.0.1", server_port)).await;
        assert!(
            connect_attempt.is_err(),
            "Server port should be closed after 3s of inactivity"
        );
        assert!(handle.is_stopped(), "Server handle should be stopped");

        let _ = std::fs::remove_dir_all(&temp_dir);
    }
}
