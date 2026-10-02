# Store and services setup

State of Google Play, Firebase, Stream and RevenueCat for the Android app, as of 2026-09-30. Update it
when the setup changes.

## Firebase

Two projects, `drafft` (`drafft-1abd3`, production) and `drafft staging` (`drafft-staging`), each with one
Android app `so.drafft.app` (same package, SHA-1 left empty). `google-services.json` goes in
`app/src/production/` and `app/src/staging/` (gitignored). Stream has a Firebase push provider named
`drafft-fcm` in each of its apps. The backend sends its own pushes through FCM with each project's service
account (`FCM_SERVICE_ACCOUNT`, drafft-backend).

## RevenueCat

Projects `drafft` (`proj3dc1aebd`, Play app `app5a04674ede`) and `drafft staging` (`proje5eb803d`, Play app
`app24119c5a87`), one Google service account for both. The public `goog_` keys are in
`config/*.properties`; the local flavor uses the staging key. Pub/Sub notifications (real-time
RevenueCat events) are not set up yet.

## Google Play

One app, `so.drafft.app`. Nine products, active: three subscriptions (one base offer each, id `base`) and six
consumables, created through the Play Developer API (`subscriptions`, `onetimeproducts`). Prices match the
App Store's wherever the currency does (127 of 174 regions).

Gotchas:

- `convertRegionPrices` treats the input as tax-exclusive: 12.99 EUR in gives 15.99 in France.
- The old `inappproducts` API is refused: use `onetimeproducts`.
- The Play Console lists a package only after its first AAB upload.

## Keys

Private keys live outside the repositories, in `~/Secrets/drafft/<service>/` (one folder per service, the
key file plus a README: key id, account, where it is used, how to revoke). Agents never open them: the
user runs whatever needs a key.
