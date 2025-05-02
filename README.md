# Android SD-JWT VCI/VP Demo

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
   _There are 3 available test users:_
    - 🇬🇷 `testuser1 / pass1`
    - 🇷🇴 `testuser2 / pass2`
    - 🇧🇬 `testuser3 / pass3`

4. **Select "Request VC"** and follow the Issuer's Authorization Code Flow to obtain a sample SD-JWT VC.  
   _The credential is securely stored in **Encrypted Shared Preferences**._

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

### TODOs

- [ ] implement scan QR code for VP (Cross-device flow)
- [ ] handle `access_token` expiration while app is in-use
