# Android SD-JWT & VCI/VP Demo
Explore how SD-JWTs, OIDC4VCI, and OIDC4VP enable user-consented, selective disclosure of Verifiable Credentials using open standards in a demo setup. 

This implementation follows (partially) the [HAIP (High Assurance Interoperability Profile)](https://openid.net/specs/openid4vc-high-assurance-interoperability-profile-1_0.html) specification.

## How to Test

### SD-JWT Issuance (VCI)

1. **"Spin up" the backend services**:
    - **Auth Server:**  
      [https://github.com/kmandalas/spring-boot-vci-vp/tree/main/auth-server](https://github.com/kmandalas/spring-boot-vci-vp/tree/main/auth-server)
    - **Issuer:**  
      [https://github.com/kmandalas/spring-boot-vci-vp/tree/main/issuer](https://github.com/kmandalas/spring-boot-vci-vp/tree/main/issuer)
    - **Verifier:**  
      [https://github.com/kmandalas/spring-boot-vci-vp/tree/main/verifier](https://github.com/kmandalas/spring-boot-vci-vp/tree/main/verifier)

2. **Build and run the app.**

3. **Authenticate using biometrics** (or PIN, pattern, passcode).

4. **Select "Request VC"** and follow the Issuer's Authorization Code Flow to obtain a sample SD-JWT VC.
   _There are 3 available test users:_
   - 🇬🇷 `testuser1 / pass1`
   - 🇷🇴 `testuser2 / pass2`
   - 🇧🇬 `testuser3 / pass3`

   🔒 _The credential is securely stored in **Encrypted Shared Preferences**._

---

### Data-sharing (VP)

1. Make sure you have a VC already stored.
2. Open your browser and go to: `https://<REPLACE_WITH_YOUR_MACHINE_IP>/verifier/invoke-wallet`
3. Press **"HAIP WALLET"** or **"OpenID4VP WALLET"**
4. Re-authenticate with biometrics if needed and follow the steps.
5. If everything is OK, you will see:
   **✅ VP Token is valid!**

#### Testing with EU Reference Verifier

You can also test with the [EU Reference Verifier](https://verifier.eudiw.dev):

1. Select credential type: **"Portable Document A1 (PDA1)"**
2. Select format: **`dc+sd-jwt`**
3. Choose attributes: `credential_holder`, `nationality`, `competent_institution`
4. Add your issuer's certificate as trusted issuer (copy from [issuer_cert.pem](https://github.com/kmandalas/spring-boot-vci-vp/blob/haip/issuer/src/main/resources/issuer_cert.pem))

---

### Demo Video

📺 You can watch a screen recording that walks through the entire flow on [YouTube](https://youtube.com/shorts/cxIgyTR8s6w).

---
<details>
<summary>⚠️ Disclaimer</summary>

This repo contains an **experimental project** created for learning and demonstration purposes. The implementation is **not intended for production** use.

</details>