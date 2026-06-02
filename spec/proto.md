Global endianness is little.
Each non-static sized data type ([]byte's) is prepanded with uint16 size. for example byte array {1,2,3,4,5} is actually
{0,5,1,2,3,4,5}

# UDP datagrams base structure:

Base structure is always plaintext

```
uint64 conn ID
uint8  frame type byte
[]byte frame body
uint32 CRC32
```

CRC32 is calculated by checksumming entire frame assuming crc32=0. Uppon receiving wrong crc32 frame, it's dropped
immidietely. It serves only and exclusively for cheap verification of frame integrity over the wires.

## UDP datagrams frame types
```
Handshake client 1
Handshake server 2
Encrypted handshake client 3
Encrypted handshake server 4
Ping frame 5
Data frame 6
Ctrl frame 7
```

# UDP datagrams frame bodies

## plaintext

Handshake (client)
```
[65]byte ECDH payload
uint8    Protocol version
```

Handshake (server response)
```
[65]byte ECDH payload
[64]byte Signature
uint8    Status
uint64   Nominated connection id
```

## Encrypted

Encrypted Handshake (client)
```
uint32   Features flag (reserved, expected 0b 00000000 00000000 00000000 00000001)
```

Encrypted Handshake (server response)
```
uint32   Features flag (reserved, expected 0b 00000000 00000000 00000000 00000001)
[32]byte ASCII (human readable) public access key [a-zA-Z0-9], case sensitive
```

Ping frame
```
uint16 ping nonce (unique in past 600 seconds)
```

Data frame
```
[]byte data (e2ee ciphertext)
```

Ctrl Frame
```
uint8  ctrlType
uint16 ctrlNonce (unique in 600s)
[]byte extra
```

Control frames are special kind of frames that are delivered reaibly.
The control nonce must be unique for timeout duration, it determines if the frame was already serviced. Most ctrl frames
(excluding ACK type, bye type) require ACK control frame response, regardless if the frame would produce more frames as
response.
The ACK frame specifies same ctrlNonce in response frame to determine which frame this frame ACKs.
Both server and client can send at any point ctrl frames. If Ctrl frame isn't ACKed, it's retransmitted with possibly
tight timeouts (such as 500ms, 2s, 5s, 10s, 30s, 60s). The specific value is unspecified.
Both server and client drop duplicate ctrl frames but transmit ACK uppon receiving duplicate frame.

If no ACK is given in timeout, connection is deemed dead.

extra is empty in most ctrl types and can house arbitrary data or even encoded struct.
for 252 it's standard ASCII err message. 
for 1 server -> client frame it contains uint64 count response. u64_max means server error, and value must be discarded.
   this condition doesn't kill connection.

### Ctrl frame types

```
ACKing frames:
1   Client- query for count of ws. Server- respond to query for count of ws

NACKing frames:
251 Server shutting down, drop connection
252 Fatal error, drop connection with human readable error message
253 Fatal error, drop connection
254 Bye
255 ACK
```

# Connection Init

prereq: client needs to fetch server's ed25519 identity. This happenes beyond scope of this protocol.
It's done via https GET, or otherwise secure / trusted connection.
Client/server MAY implement request for identity, for example:
GET /identity
Response (application/json):
```json
{
    "ed25519_pub": "<32-hex-char>"
}
```

1. client sends handshake data, with connID cryptographically random (store for later), provides ECDH key to exchange
   - server validates protocol version and calculates exchanged key locally.
   - if invalid protocol version but otherwise marshals correctly responds with status:
     - protocol version too low = 255
     - protocol version too high = 254
     - otherwise = 0
     - other values invalid / reserved for future
   - server calculates keys (see key calculation)
   - server nominates connection id
2. server responds with own ECDH public key and stores connection into connections map // todo: possible DoS maybe?
    - client calculates own keypair and further inner frames are encrypted (see encrpytion)
    - client replaces it's random connection id with one requested by server
3. client first checks the signature against locally cached public key of server. If signature is wrong, the connection 
   is considered dead and client will not send any more frames. Client sends its features flags as proof of valid encryption.
    - for now only last bit of features flag is true, it stands for the mvp feature. If server finds other value, dropps
      connection and deletes it from connections map
    - server generates 32 bytes of human-readable characters and starts configuring ws logic for serving it in background 
    - uppon receive, server sets current timeout from 30s to 600s and flags connection as valid.
4. server responds with final handshake frames. Server now expects pings and will drop connection if no frame is received within timeout

## Key calculation

```pseudocode
lpk = ecdh.P256.GenerateKey()
rpk = ecdh.P256.PublicKey(remote.ecdh)
sk  = sha.512(lpk.ecdh(rpk) + server_generated_connection_id + client_generated_connection_id)
# Key 1 is server key. Key 2 is client key. later it's as kN to represent both
k1  = sk[:32]
k2  = sk[32:]

aes = aes.New(kN).GCM()
```
## Signature calculation

```
client_conn_id = little_endian(client_conn_id_u64)
server_conn_id = little_endian(server_conn_id_u64)
signature = ed25519.Sign(server_identity, []byte( client_ecdh_pubkey + server_ecdh_pubkey + client_conn_id + server_conn_id ))
```

## Encryption

Each frame is marshaled as `[12]bytes nonce + []bytes ciphertext`, *without* length prefix. CRC location is determined
by total length of frame - 4 bytes. Nonce is based on frame type and uint64 frame counter. If counter >= uint64_max - 100, send
ctrl frame 253 and kill connection. 
Nonce is {0,0,0,frame_type,counter...}
Ciphertext includes GCM tag, appended at the end

Note: parties may rely on last 8 bytes of nonce being continous to prevent replay attacks in the future, so the order
must be kept or 253 frame may be received.

# Pinging

Its always client that initiates ping.
Client sends ping to server and expects pong with same nonce.
Pings are noops but should be send even if there is other data transfer that would bump connection's last seen preventing
timeouting.