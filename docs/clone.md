# Clone: phone-to-phone glucose sharing

Clone sends glucose data from one JugglucoNG phone to another without a
Nightscout server or user account. The phone connected to the physical sensor
is the **sender**. The other phone is the **receiver**.

For most people, the shortest setup is:

1. Install the same JugglucoNG version on both phones.
2. On the sender, open **Settings > Exchange data > Mirror**.
3. Tap **Share Hybrid QR**.
4. On the receiver, open the same screen, tap **Scan QR**, scan the code, and
   confirm the connection.
5. Wait for a fresh reading on the receiver before disabling another follower
   source such as Nightscout.

Do not scan or pair the physical sensor on the receiver. Clone creates and
maintains its own receiver-side sensor record.

## Local QR or Hybrid QR?

**Local QR** is the simple choice when both phones always stay on the same
local network. It does not use an internet rendezvous server, STUN, or TURN.

**Hybrid QR** is the normal choice for phones that leave home. It tries a
direct ICE connection first and uses TURN only when a direct path cannot be
formed. A direct path can be local or cross the internet, depending on the two
networks and their NAT/firewall behavior.

Hybrid Clone uses three small network services:

| Service | Purpose | Sees |
| --- | --- | --- |
| Rendezvous | Lets both phones exchange ICE descriptions | Connection label, timing, and candidate IP metadata |
| STUN | Tells each phone how its UDP socket appears on the internet | Client IP and UDP port |
| TURN | Relays packets when a direct route does not work | Endpoint metadata, timing, and encrypted packet sizes |

The rendezvous server is signaling only. Glucose data does not pass through
it. STUN is discovery only. TURN can carry the data stream, but only as a relay.
For a visual explanation of how the services work together, see
[ICE, STUN, TURN, and rendezvous in plain language](ice-stun-turn.md).

While Hybrid Clone is connected, the receiver keeps a lightweight HTTPS watch
open with the rendezvous server. If the sender changes networks, that watch
notifies the receiver about the sender's new ICE generation without waiting
for the old path to time out. If the server does not support the watch or is
temporarily unavailable, Clone falls back to normal ICE failure detection and
rendezvous polling.

## Security and privacy

Quick Pair generates a random Clone connection password. The Mirror protocol
uses authenticated ASCON encryption when that password is present, including
when packets travel through TURN. TURN therefore does not need TLS to keep the
glucose payload private from the relay operator.

The rendezvous connection uses HTTPS. Certificate chain and hostname
verification are enabled by default. Give the server a publicly trusted
certificate for the hostname entered in JugglucoNG when possible. A trusted
self-signed server can be used by disabling certificate verification, but that
also removes rendezvous server authentication and permits active interception
of ICE signaling. The Clone data stream remains separately encrypted.

The Hybrid QR is secret. It contains the Clone connection password and, when
configured, the TURN username and password. Share it directly with the intended
receiver. Do not publish the QR or a screenshot of it. If it leaks, delete the
Clone connection and rotate the TURN credentials.

Self-hosting removes the default rendezvous and STUN operators from the path,
but it does not hide normal IP and traffic metadata from networks between the
phones and your server.

## Day-to-day use

The **Clone** switch enables or disables all saved Clone connections. Turning
it off keeps the connection definitions but stops Clone reception and removes
the live receiver-side Clone sensor records. It does not erase historical
glucose entries.

Deleting a connection is different: it removes that saved pairing. A new QR
scan is then required to recreate it.

**Keep Clone live in background** is optional and intended mainly for a
receiver that must get readings and alerts with minimum delay. It holds a
partial Android wake lock while an enabled receiver connection exists. This
improves background liveness but uses more battery. Without it, short gaps can
occur while Android sleeps; Clone should reconnect and fill the gap when it can
run again.

**Broadcast on Network** is not required for either QR method. It only enables
local discovery so nearby devices can find the phone without scanning a QR.
Changing this option does not replace an already working Clone connection. The
saved choice is used the next time that connection negotiates.

The other Hybrid transport switches also take effect on the next connection
attempt. Changing a server address, port, username, password, or which
rendezvous server is selected reconnects Clone immediately so corrected
server details do not remain unused.

Use one normal follower path at a time. A brief overlap with Nightscout is
useful while validating Clone, but leaving both enabled can make duplicated or
relabelled glucose and treatment history harder to understand because the two
sources arrive at different times.

## Quick self-hosting

The following deployment runs:

- `jugglucoconnect` as the HTTPS rendezvous service on TCP port 6789
- coturn as both STUN and TURN on UDP port 3478
- a small UDP relay range, ports 49160 through 49199

It assumes the server owns its public IP directly. Replace
`clone.example.com`, the example IP addresses, username, and password.

### 1. Prepare the directory and certificate

This example uses `/opt/jugglucong`:

```sh
mkdir -p /opt/jugglucong/rendezvous
cd /opt/jugglucong
```

Find the certificate lineage with `certbot certificates`. Then copy the
certificate and key so the unprivileged rendezvous container can read them:

```sh
install -m 0640 -o root -g 65534 \
  /etc/letsencrypt/live/clone.example.com/fullchain.pem \
  /opt/jugglucong/rendezvous/fullchain.pem

install -m 0640 -o root -g 65534 \
  /etc/letsencrypt/live/clone.example.com/privkey.pem \
  /opt/jugglucong/rendezvous/privkey.pem
```

`install` copies the file and sets its owner and permissions in one command.
The copied private key is readable only by root and GID 65534, which is the
container account.

### 2. Create `docker-compose.yml`

```yaml
services:
  coturn:
    image: coturn/coturn:4.17.2-r0
    restart: unless-stopped
    network_mode: host
    command: ["-c", "/etc/coturn/turnserver.conf"]
    logging:
      driver: local
      options:
        max-size: "1m"
        max-file: "2"
    volumes:
      - type: bind
        source: ./turnserver.conf
        target: /etc/coturn/turnserver.conf
        read_only: true
        bind:
          create_host_path: false

  jugglucoconnect:
    image: docker.io/pannal/jugglucoconnect-open:2809ec2
    restart: unless-stopped
    stop_grace_period: 3s
    read_only: true
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
    logging:
      driver: local
      options:
        max-size: "1m"
        max-file: "2"
    ports:
      - "6789:6789/tcp"
    volumes:
      - type: bind
        source: ./rendezvous/fullchain.pem
        target: /data/fullchain.pem
        read_only: true
        bind:
          create_host_path: false
      - type: bind
        source: ./rendezvous/privkey.pem
        target: /data/privkey.pem
        read_only: true
        bind:
          create_host_path: false
```

The rendezvous image source and container deployment are available in the
[`pannal/jugglucoconnect` repository](https://github.com/pannal/jugglucoconnect/tree/deploy/container).

### 3. Create `turnserver.conf`

Generate a long password that is easy to paste into the config:

```sh
openssl rand -hex 32
```

Use the generated value below. The documentation addresses are examples and
must be replaced with addresses assigned to the server:

```ini
# Listen and relay only on addresses that belong to this host.
listening-ip=203.0.113.10
relay-ip=203.0.113.10

# If the server has public IPv6, add both of these too.
# listening-ip=2001:db8::10
# relay-ip=2001:db8::10

listening-port=3478
min-port=49160
max-port=49199

fingerprint
lt-cred-mech
realm=clone.example.com
user=cloneuser:REPLACE_WITH_THE_GENERATED_SECRET
stale-nonce=600

# JugglucoNG currently uses UDP TURN.
no-tcp
no-tls
no-tcp-relay

no-cli
no-multicast-peers
log-file=stdout
```

Do not add `no-stun`: the same coturn listener supplies STUN. `no-dtls` is not
needed on current coturn releases because DTLS is off unless explicitly
enabled.

`stale-nonce=600` rotates TURN authentication nonces after ten minutes. It is
not a ten-minute allocation or connection timeout. Clients can answer the
stale-nonce challenge and continue.

The 40-port relay range leaves room for old and new allocations to overlap
during network changes and repeated ICE negotiations. Ten ports can be
exhausted by this normal churn before coturn releases the older allocations.
TURN authentication still applies to every allocation in the larger range.

If the public address is not assigned directly to the host, coturn needs a
different NAT configuration. Set `listening-ip` and `relay-ip` to the private
host address and add `external-ip=PUBLIC_IP/PRIVATE_IP`.

### 4. Open only the required ports

With UFW:

```sh
ufw allow 6789/tcp comment 'Clone rendezvous'
ufw allow 3478/udp comment 'Clone STUN and TURN'
ufw allow 49160:49199/udp comment 'Clone TURN relay'
ufw status
```

No coturn TCP, TLS, or DTLS port is needed for the current JugglucoNG client.
The HTTPS certificate belongs to the rendezvous service on TCP port 6789.

### 5. Start and check the services

```sh
docker compose config
docker compose up -d
docker compose ps
docker compose logs --since=2m coturn jugglucoconnect
```

Useful checks are:

```sh
curl --fail --show-error https://clone.example.com:6789/

docker compose exec coturn sh -c \
  'test -f /etc/coturn/turnserver.conf && echo config-ok'
```

Test STUN from a different internet connection if `turnutils_stunclient` is
available:

```sh
turnutils_stunclient clone.example.com -p 3478
```

The coturn startup log should show the configured realm and only the explicit
listener and relay addresses. It must not say that the config file is missing.

### 6. Reload the certificate after renewal

Create `/etc/letsencrypt/renewal-hooks/deploy/40-jugglucoconnect`:

```sh
#!/bin/sh
set -eu

CERT_LINEAGE=/etc/letsencrypt/live/clone.example.com
DEST=/opt/jugglucong/rendezvous

[ "${RENEWED_LINEAGE:-}" = "$CERT_LINEAGE" ] || exit 0

install -m 0640 -o root -g 65534 \
  "$CERT_LINEAGE/fullchain.pem" "$DEST/fullchain.pem"
install -m 0640 -o root -g 65534 \
  "$CERT_LINEAGE/privkey.pem" "$DEST/privkey.pem"

docker compose -f /opt/jugglucong/docker-compose.yml \
  up -d --no-deps --force-recreate jugglucoconnect
```

Make the hook executable:

```sh
chmod 0750 /etc/letsencrypt/renewal-hooks/deploy/40-jugglucoconnect
```

Adjust `CERT_LINEAGE` if Certbot reports a suffix such as `-0001`.

## Configure JugglucoNG for the self-hosted services

Do this on the sender before displaying the Hybrid QR:

1. Open **Settings > Exchange data > Mirror > TURN Server**.
2. Enter the TURN hostname, username, and password. Leave the default UDP port
   `3478` unless the server uses another port.
3. Leave **Use TURN server for STUN** enabled. It defaults to on for a new TURN
   setup.
4. Enable **Use custom rendezvous server**, enter its hostname, and leave the
   default port `6789` unless the server uses another port.
5. Leave **Verify rendezvous certificate** enabled for a publicly trusted
   certificate. Disable it only for a self-signed server that you trust.
6. Save, return to the Clone screen, and tap **Share Hybrid QR**.
7. Scan that Hybrid QR on the receiver and confirm its warning.

The Hybrid QR carries the TURN credentials, the STUN choice, the rendezvous
endpoint, and its certificate-verification choice. The receiver does not need
those fields entered manually. Importing the QR replaces its existing TURN and
Clone ICE service settings.

Older Hybrid QRs without the STUN and rendezvous fields remain valid, but they
continue to use the app defaults. Generate a new QR after changing any server
setting.

## Verify the route

First confirm a fresh reading while both phones are on the same Wi-Fi. Then put
one phone on mobile data and leave the other on Wi-Fi. The connection details
should eventually report a completed ICE state, and the receiver-side sensor
shows whether the current reading arrived through local ICE or TURN.

Different networks do not guarantee TURN. ICE still prefers a working direct
path to reduce relay traffic. TURN appears only when direct candidates fail.

If the receiver is current and stable, disable its old Nightscout follower. If
Clone later needs to be taken out of service, disable Clone on the receiver and
re-enable Nightscout.

## Troubleshooting

- **`Cannot find credentials of user` in coturn:** the app and coturn usernames
  differ, or coturn did not load the intended config.
- **coturn reports a missing config:** check that `turnserver.conf` is a file,
  not a directory, and keep the explicit Compose `command` shown above.
- **coturn reports `errno=98` followed by `no available ports`:** its relay
  range is exhausted. Use at least the 40-port range in this guide and make
  sure the complete range is open in the firewall.
- **ICE gathers candidates but fails:** check DNS, UDP firewall rules, TURN
  credentials, and whether a VPN blocks UDP.
- **The same-LAN test works but remote sharing does not:** verify TCP 6789, UDP
  3478, and the full UDP relay range from outside the server network.
- **The receiver sleeps and updates arrive late:** remove Android battery
  restrictions first. If minimum alert delay matters, enable **Keep Clone live
  in background** on the receiver.
- **A Clone sensor appears but has no live data:** do not activate or pair the
  physical sensor on the receiver. Check that Clone and the saved connection
  are enabled, then use **Reconnect all**.

Clone is a transport, not a backup strategy. Keep normal database backups even
when reconnection and short-gap synchronization work reliably.
