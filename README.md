# Android SD-JWT VCI/VP Demo

## How to Test

### SD-JWT Issuance (VCI)

1. **"Warm up" backend services** (if inactive) by hitting the following URLs:
    - **Auth Server:** [https://vc-auth-server.onrender.com/.well-known/openid-configuration](https://vc-auth-server.onrender.com/.well-known/openid-configuration)
    - **Issuer:** [https://vc-issuer.onrender.com/.well-known/openid-credential-issuer](https://vc-issuer.onrender.com/.well-known/openid-credential-issuer)
    - **Verifier:** [https://vp-verifier.onrender.com/verifier/invoke-wallet](https://vp-verifier.onrender.com/verifier/invoke-wallet)

2. **Build and run the app.**

3. **Authenticate using biometrics** (or PIN, pattern, passcode).

4. **Select "Request VC"** to obtain a sample SD-JWT VC.
    - The credential is securely stored in **Encrypted Shared Preferences**.


### Data-sharing (VP)

1. Make sure you have a VC already stored
2. Open Browser and go to: https://vp-verifier.onrender.com/verifier/invoke-wallet
3. Press "OPEN WITH YOUR WALLET"
4. Re-authenticate with biometrics if needed and follow the steps
5. If everything is OK you will get a "✅ VP Token is valid!" message on screen

### TODOs

- fix error: "kotlinx.coroutines.JobCancellationException: Parent job is Completed;"
- display more info when selecting claims during VP
- implement scan QR code for VP