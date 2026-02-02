# Android SD-JWT & VCI/VP Demo
Explore how SD-JWTs, OIDC4VCI, and OIDC4VP enable user-consented, selective disclosure of Verifiable Credentials using open standards in a demo setup. The wallet implements wallet attestation (WIA/WUA), DPoP-bound tokens, and HAIP-compliant verifiable presentations, following the [HAIP (High Assurance Interoperability Profile)](https://openid.net/specs/openid4vc-high-assurance-interoperability-profile-1_0.html) specification and EUDI Architecture Reference Framework.

Related articles:
- [Verifiable Credentials with Spring Boot & Android](https://dzone.com/articles/verifiable-credentials-spring-boot-android)
- [Securing Verifiable Credentials with DPoP](https://dzone.com/articles/securing-verifiable-credentials-with-dpop-spring-boot)
- [HAIP 1.0: Securing Verifiable Presentations](https://dzone.com/articles/haip-1-0-securing-verifiable-presentations)
- More articles covering WIA, WUA, and Token Status List coming soon.

## Features

| Feature | Description |
|---------|-------------|
| **WIA** | Obtains Wallet Instance Attestation from the wallet-provider and presents it at PAR/Token endpoints via `attest_jwt_client_auth` |
| **WUA** | Obtains Wallet Unit Attestation backed by Android Key Attestation (TEE/StrongBox) for hardware key security |
| **PAR** | Uses Pushed Authorization Requests before initiating the Authorization Code Flow |
| **DPoP** | Generates DPoP proofs to sender-constrain access tokens (RFC 9449) |
| **HAIP VP** | Verifies JAR signatures (x5c), validates `x509_hash` client_id, encrypts VP responses (ECDH-ES + A256GCM) |
| **DCQL** | Parses Digital Credentials Query Language requests for selective disclosure |
| **dc+sd-jwt** | Issues and presents credentials in HAIP-compliant format with x5c header |

<details>
<summary>🚧 <strong>WIP / Under Construction</strong></summary>

### Current status
- **Most recent branch:** `wia`

### Follow-up DZone articles
- [Securing Verifiable Credentials with DPoP (Spring Boot)](https://dzone.com/articles/securing-verifiable-credentials-with-dpop-spring-boot)
- [HAIP 1.0 – Securing Verifiable Presentations](https://dzone.com/articles/haip-1-0-securing-verifiable-presentations)

</details>


## How to Test

### Backend Services

Start the backend services from [spring-boot-vci-vp](https://github.com/kmandalas/spring-boot-vci-vp): auth-server (port 9000), issuer (port 8080), verifier (port 9002), and wallet-provider (port 9001).

### SD-JWT Issuance (VCI)

1. **Build and run the app.**

2. **Authenticate using biometrics** (or PIN, pattern, passcode).

3. **Select "Request VC"** and follow the Issuer's Authorization Code Flow to obtain a sample SD-JWT VC.
   _There are 3 available test users:_
   - `testuser1 / pass1`
   - `testuser2 / pass2`
   - `testuser3 / pass3`

   The credential is securely stored in **Encrypted Shared Preferences**.

---

### Data-sharing (VP)

1. Make sure you have a VC already stored.
2. Open your browser and go to: `https://<REPLACE_WITH_YOUR_MACHINE_IP>/verifier/invoke-wallet`
3. Press **"HAIP WALLET"** or **"OpenID4VP WALLET"**
4. Re-authenticate with biometrics if needed and follow the steps.
5. If everything is OK, you will see:
   **VP Token is valid!**

#### Testing with EU Reference Verifier

You can also test with the [EU Reference Verifier](https://verifier.eudiw.dev):

1. Select credential type: **"Portable Document A1 (PDA1)"**
2. Select format: **`dc+sd-jwt`**
3. Choose attributes: `credential_holder`, `nationality`, `competent_institution`
4. Add your issuer's certificate as trusted issuer (copy from [issuer_cert.pem](https://github.com/kmandalas/spring-boot-vci-vp/blob/haip/issuer/src/main/resources/issuer_cert.pem))

---

### Demo Video

You can watch a screen recording that walks through the entire flow on [YouTube](https://youtube.com/shorts/cxIgyTR8s6w).

_Note: The video predates recent UI additions (WIA/WUA attestation cards, PDA1 display enhancements). An updated recording is planned._

---
<details>
<summary>Disclaimer</summary>

This repo contains an **experimental project** created for learning and demonstration purposes. The implementation is **not intended for production** use.

</details>
