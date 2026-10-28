# CN phone mobile secure transport

This separates the general CN account/activation migration from the experimental OTA feature
in PR #739. It changes ordinary CN phone mobile calls, including login, account lookup,
validation, Active Auth and bind/unbind. No firmware UI, BLE OTA writes or journal changes
are included.

The supplied activation screenshot reports HTTP 403 `NET_SECURITY_PLAINTEXT_ROUTE_FORBIDDEN`.
The earlier OTA trace reports HTTP 200 with `签名校验失败` when the exchange signed the stored
ID suffix instead of the complete outgoing identity. The dispatcher now uses the complete
`ottai:a:n:…` / `ottai:a:g:…` identity, the recovered CN 1.63.0 / 264122 contract and
`cipherUserId`. The P-256/HKDF/AES-GCM codec is already on main. Other backends keep main's
regional transport. There is no plaintext retry after a secure request.

Synthetic encrypted-server tests cover actual common HTTP dispatch, escaped queries,
POST/PUT bodies, signed timestamps, business-code fallbacks, token rejection and empty or
tampered responses. The optional `scripts/ottai/SecureCloudProbe.kt` performs only an
anonymous API-token round trip and retains no returned token or ephemeral key.

## Pending signed-in test

This PR is a draft until a signed-in CN phone account test is performed. Anonymous and
synthetic tests do not establish working account login, sensor materials, binding or physical
activation. On the phone, verify CN sign-in, current/history device lookup, sensor validation
and the existing credential/bootstrap path before normal activation. No real account test
has been performed by the agent, and no sensor operation is performed by the probe.
