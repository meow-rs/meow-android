//! Helpers shared by the unit tests of more than one module.

use parking_lot::Mutex;
use std::net::SocketAddr;

/// Held by every test that touches the process-wide engine state: the
/// tun2socks session, `ENGINE`, `HOME_DIR` and `DNS_RESOLVER`.
pub(crate) static SERIAL: Mutex<()> = Mutex::new(());

/// What [`txt_nameserver`] answers every query with.
pub(crate) const PROFILE_TXT: &[u8] = b"from-the-profile-nameserver";

/// Where a DNS message's question ends: the header, the name, then
/// qtype and qclass.
fn question_end(msg: &[u8]) -> Option<usize> {
    let mut pos = 12;
    loop {
        let len = usize::from(*msg.get(pos)?);
        pos += 1;
        if len == 0 {
            break;
        }
        pos += len;
    }
    Some(pos + 4).filter(|&end| end <= msg.len())
}

/// A UDP nameserver on loopback that answers every query with one TXT
/// record holding [`PROFILE_TXT`].
pub(crate) async fn txt_nameserver() -> SocketAddr {
    let socket = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let addr = socket.local_addr().unwrap();
    tokio::spawn(async move {
        let mut buf = [0u8; 1500];
        while let Ok((n, peer)) = socket.recv_from(&mut buf).await {
            let Some(end) = question_end(&buf[..n]) else {
                continue;
            };
            let mut reply = buf[..end].to_vec();
            reply[2..4].copy_from_slice(&0x8180u16.to_be_bytes()); // response, RD, RA
            reply[6..12].copy_from_slice(&[0, 1, 0, 0, 0, 0]); // one answer
            reply.extend_from_slice(&[0xc0, 0x0c, 0, 16, 0, 1, 0, 0, 0, 60]);
            reply.extend_from_slice(&(PROFILE_TXT.len() as u16 + 1).to_be_bytes());
            reply.push(PROFILE_TXT.len() as u8);
            reply.extend_from_slice(PROFILE_TXT);
            let _ = socket.send_to(&reply, peer).await;
        }
    });
    addr
}

/// A DNS query for `name`'s records of `qtype`.
pub(crate) fn dns_query(name: &str, qtype: u16) -> Vec<u8> {
    let mut dns = vec![0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    for label in name.split('.') {
        dns.push(label.len() as u8);
        dns.extend_from_slice(label.as_bytes());
    }
    dns.push(0);
    dns.extend_from_slice(&qtype.to_be_bytes());
    dns.extend_from_slice(&1u16.to_be_bytes());
    dns
}

/// Whether a DNS reply carries [`PROFILE_TXT`].
pub(crate) fn from_the_profile_nameserver(reply: &[u8]) -> bool {
    reply.windows(PROFILE_TXT.len()).any(|w| w == PROFILE_TXT)
}
