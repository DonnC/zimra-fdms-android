# Contributing to fzimra

Thank you for taking the time to contribute. fzimra produces tax records, so
correctness is the first concern in every change.

## Before you start

- **Bugs:** open an issue with the fzimra version, the Android version and
  device, what you did, what you expected and what happened. If ZIMRA rejected
  a receipt, include the error code and its colour.
- **Features:** open an issue first to agree on the approach before writing
  code.
- **Security problems:** do not open an issue. Follow [SECURITY.md](SECURITY.md).

Never post private keys, certificates, activation keys, real taxpayer TINs or
real receipts in an issue or pull request. Redact them.

## Development setup

1. Install a JDK 17 or later and the Android SDK with platform 36. Android
   Studio is optional.
2. Create `local.properties` in the project root:

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

3. Check that the build passes:

   ```bash
   ./gradlew build
   ```

## Rules for changes

- **Tests are required.** Every change in behaviour comes with tests that
  prove it, and every bug fix with a test that fails without the fix.
- **Keep `core` free of Android.** Code in `org.donnc.fzimra.core` must not
  import Android APIs. `CorePurityTest` enforces this.
- **Follow ZIMRA's specification exactly.** Any change to what is signed,
  numbered or sent to ZIMRA must match the Fiscal Device Gateway API and come
  with tests that prove it, against the specification's own examples where
  they exist.
- **Public API is explicit.** The library builds in Kotlin's explicit API
  mode: every public declaration states its visibility and type, and is
  documented with KDoc.
- **No new dependencies** without discussion in an issue first.

## Code style

- Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
  and the project's `.editorconfig`.
- Prefer immutable data and pure functions in `core`.
- Comments explain *why*. The code shows *what*.

## Commits and pull requests

- Use [Conventional Commits](https://www.conventionalcommits.org/) for commit
  messages, for example `fix(core): ...` or `docs: ...`.
- Keep a pull request to one logical change, and describe what it changes and
  how it was tested.
- Add an entry under **Unreleased** in [CHANGELOG.md](CHANGELOG.md) for any
  user-visible change.
- `./gradlew build` must pass before review.

## Licence

By contributing, you agree that your contributions are licensed under the
project's [MIT licence](LICENSE).
