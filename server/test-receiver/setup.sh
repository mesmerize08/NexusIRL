#!/bin/sh
# Sets up an SRTLA test receiver on a fresh Ubuntu 24.04 server, as root:
#   - SSH: key logins only; firewall: SSH and UDP 5000 only; automatic security updates
#   - tools/srtla-receiver on UDP 5000, relaying to an SRT listener on localhost:4001
#   - ffmpeg recording that listener (encrypted with a random passphrase) in 10-minute segments,
#     deleted after 2 days
# Usage: copy tools/srtla-receiver/build/install/srtla-receiver to /tmp/srtla-receiver, then run
# this script. The SRT passphrase is in /etc/nexusirl/srt-passphrase. See README.md.
set -eu

cat > /etc/ssh/sshd_config.d/00-nexusirl.conf <<EOF
# Key-only logins. The first file wins over cloud-init defaults.
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
EOF
sshd -t
systemctl reload ssh

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get -y -qq upgrade
apt-get -y -qq install ufw unattended-upgrades openjdk-17-jre-headless ffmpeg
echo "unattended-upgrades unattended-upgrades/enable_auto_updates boolean true" | debconf-set-selections
dpkg-reconfigure -f noninteractive unattended-upgrades

ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment "SSH"
ufw allow 5000/udp comment "SRTLA test receiver"
ufw --force enable

id nexusirl >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin nexusirl
mkdir -p /opt/nexusirl /etc/nexusirl /var/lib/nexusirl/recordings
rm -rf /opt/nexusirl/srtla-receiver
cp -r /tmp/srtla-receiver /opt/nexusirl/srtla-receiver
chown -R root:root /opt/nexusirl
chown nexusirl:nexusirl /var/lib/nexusirl/recordings
chmod 750 /var/lib/nexusirl/recordings
if [ ! -f /etc/nexusirl/srt-passphrase ]; then
    tr -dc "A-Za-z0-9" </dev/urandom | head -c 32 > /etc/nexusirl/srt-passphrase
fi
chown root:nexusirl /etc/nexusirl/srt-passphrase
chmod 640 /etc/nexusirl/srt-passphrase

cat > /opt/nexusirl/record.sh <<'EOF'
#!/bin/sh
# SRT listener on localhost only; the SRTLA receiver relays the bonded paths to it.
exec ffmpeg -nostdin -hide_banner -loglevel warning \
  -i "srt://127.0.0.1:4001?mode=listener&latency=2000000&passphrase=$(cat /etc/nexusirl/srt-passphrase)" \
  -c copy -f segment -segment_time 600 -reset_timestamps 1 -strftime 1 \
  /var/lib/nexusirl/recordings/%Y%m%d-%H%M%S.ts
EOF
chmod 755 /opt/nexusirl/record.sh

hardening="NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes"

cat > /etc/systemd/system/nexusirl-record.service <<EOF
[Unit]
Description=NexusIRL test: record the SRT stream (localhost:4001)
After=network-online.target
[Service]
User=nexusirl
ExecStart=/opt/nexusirl/record.sh
Restart=always
RestartSec=2
$hardening
ReadWritePaths=/var/lib/nexusirl/recordings
[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/nexusirl-srtla.service <<EOF
[Unit]
Description=NexusIRL test: SRTLA receiver (UDP 5000) relaying to localhost:4001
After=network-online.target nexusirl-record.service
[Service]
User=nexusirl
Environment=JAVA_OPTS=-Xmx192m
ExecStart=/opt/nexusirl/srtla-receiver/bin/srtla-receiver --srt-port 4001 --srtla-port 5000
Restart=always
RestartSec=2
$hardening
[Install]
WantedBy=multi-user.target
EOF

echo "d /var/lib/nexusirl/recordings 0750 nexusirl nexusirl 2d" > /etc/tmpfiles.d/nexusirl.conf
systemctl daemon-reload
systemctl enable --now nexusirl-record.service nexusirl-srtla.service
echo "Done. Stream to srtla://<this server>:5000?passphrase=<contents of /etc/nexusirl/srt-passphrase>"
