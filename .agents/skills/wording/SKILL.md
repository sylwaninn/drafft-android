---
name: wording
description: |
  drafft's editorial rules for any text people read. Use it BEFORE writing, editing, reviewing or
  translating user-facing copy: UI strings, microcopy, buttons/CTAs, onboarding, empty states,
  errors, alerts, push notifications, emails, SMS, paywall, App Store / Play Store listings,
  website/landing copy, screenshots and mockups (profiles, bios, chat messages), social posts,
  taglines, marketing content, core/model i18n (android/*.json) changes, in any of the 7 languages.
  Triggers: wording, copy, copywriting, microcopy, UX writing, texte, libellé, traduction,
  translation, tagline, slogan, CTA, notification, email, paywall, store listing, landing page.
---

# drafft wording

`WORDING.md` at the repository root is the only source of truth. This skill enforces it; it
restates no rule.

1. **Read `WORDING.md` in full** before writing a word (it's short). Don't rely on memory: it
   changes, and section 11 logs the latest decisions.
2. **Write** with its positioning (§1), personality and tone for the context (§2–3), lexicon (§4)
   and form rules (§6). Every language you touch gets all 7 (en fr es de it pt nl), adapted, not
   literal.
3. **Check the forbidden list (§5)** on every string, in every language. Above all: never the word
   "plan" (any sense, any language) and never a match presented as turning into / ending in /
   leading to something.
4. **Run the review checklist (§10)** and fix anything that fails. Then run the repository's
   automated check:
   - drafft-android: `python3 scripts/ci/i18n_lint.py`, `python3 scripts/check-strings.py` and
     `./gradlew -p tools/jvmcheck test`
     (`LocalizationTest`: every Android variant still matches a catalog key, in the 7 languages)
   - the app's strings themselves are checked in drafft: `python3 scripts/ci/i18n_lint.py` there
5. **Report** in your answer which WORDING.md sections you applied and any rule you had to bend
   (with the reason), so the human can decide.

Where the text lives in drafft-android:
- App strings come from the iPhone catalog (`drafft/Drafft/Resources/Localizable.xcstrings`), key for
  key. A new or changed string is written there first (with the `wording` skill in drafft), then
  `python3 scripts/sync-strings.py` copies it here. Never add a key only on Android.
- The one text written here: the Android wording of the iPhone's platform sentences (App Store,
  Apple Account, iPhone Settings) in `core/model/src/main/resources/i18n/android/<lang>.json`, all 7
  languages, same key as the catalog. Same rules as any copy.

A new editorial decision (new term, new banned phrase, validated tagline) goes into `WORDING.md`
§11 and the relevant section, in `drafft` only, then `scripts/sync-wording.sh` copies it to
drafft-backend, drafft-web and drafft-android. `WORDING.md` here is that copy: never edit it here.
Never copy rules into other files.
