# Android SD-JWT & VCI/VP Demo
Explore how SD-JWTs, OIDC4VCI, and OIDC4VP enable user-consented, selective disclosure of Verifiable Credentials using open standards in a demo setup.
Full related article [here](https://dzone.com/articles/verifiable-credentials-spring-boot-android)

<details>
<summary>🚧 <strong>WIP / Under Construction</strong></summary>

### Current status
- **Most recent branch:** `wia`

### Follow-up DZone articles
- [Securing Verifiable Credentials with DPoP (Spring Boot)](https://dzone.com/articles/securing-verifiable-credentials-with-dpop-spring-boot)
- [HAIP 1.0 – Securing Verifiable Presentations](https://dzone.com/articles/haip-1-0-securing-verifiable-presentations)

</details>


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
2. Open your browser and go to:  
   `https://<REPLACE_WITH_YOUR_MACHINE_IP>/verifier/invoke-wallet`
3. Press **"OPEN WITH YOUR WALLET"**
4. Re-authenticate with biometrics if needed and follow the steps.
5. If everything is OK, you will see:  
   **✅ VP Token is valid!**

---

### Demo Video

📺 You can watch a screen recording that walks through the entire flow on [YouTube](https://youtube.com/shorts/cxIgyTR8s6w).


### TODOs

- [ ] handle `access_token` expiration while app is in-use
- [ ] implement scan QR code for VP and the "Cross-device" flow in general
- [ ] incorporate [EUDI Presentation Exchange v2 library](https://github.com/eu-digital-identity-wallet/eudi-lib-jvm-presentation-exchange-kt)
- [ ] incorporate [EUDI SD-JWT library](https://github.com/eu-digital-identity-wallet/eudi-lib-jvm-sdjwt-kt)
- [ ] incorporate [EUDI OpenId4VCI library](https://github.com/eu-digital-identity-wallet/eudi-lib-jvm-openid4vci-kt)

---
<details>
<summary>⚠️ Disclaimer</summary>

This repo contains an **experimental project** created for learning and demonstration purposes. The implementation is **not intended for production** use.

</details>
