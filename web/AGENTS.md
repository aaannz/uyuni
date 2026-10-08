# `web/` — Uyuni web frontend (`spacewalk-web`)

Loaded when working in this subtree; root `AGENTS.md` and `AGENTS_CUSTOM.md` still apply.

## Scope and ownership

npm package `spacewalk-web`: the React/TypeScript UI served by the Java server.
Requires Node `22.x`, npm `>=10.x`; use npm, not yarn. The repo-root `package.json` is
only a convenience wrapper: every script is `npm --prefix web run <script>`.

## Directory layout

- `web/html/src/components/` — reusable components; `core/` (auth, channels, intl, spa...);
  `manager/` (feature pages: admin, systems, audit, ...); `utils/` (shared utils + test
  utils); `branding/` (stylesheets, fonts, images).
- `web/html/javascript/` — legacy global scripts; `web/po/` — translations;
  `web/html/src/build/` — webpack/storybook/license tooling.
- `web/eslint-local-rules/` — project ESLint plugin (`local-rules` in
  `web/eslint.config.js`): `no-raw-date` (use `localizedMoment()`, not `new Date`/`moment`)
  and `intl-apostrophe-curly` (ICU escaping in `t()` strings).
- `web/conf/` — `rhn_web.conf`: server-side web-UI config defaults (versions, session
  settings, SPA flags); installed to config-defaults by the RPM.
- `web/modules/` — legacy server-side Perl (`PXT::`, `RHN::`), NOT part of the JS build;
  built by `web/Makefile.spacewalk-web` and shipped in the `spacewalk-base` subpackage.
- Jest tests are co-located next to sources as `*.test.ts(x)`.
- `web/html/src/dist/` — build output (generated); never edit or commit. (Note:
  `web/README.md` mentions a stale web/html/dist output path — the real output dir is
  `web/html/src/dist/` per `web/html/src/build/webpack/config.js`.)

## Entry points

- Webpack entry point defined in `web/html/src/build/webpack/config.js` (resolves to
  `web/html/src/manager/index.ts`); CSS entries
  from `branding/css/*.scss`; output to `dist`.
- Import aliases `branding/*`, `components/*`, `core/*`, `manager/*`, `utils/*` map to
  sibling dirs of `web/html/src` (`web/html/src/tsconfig.json`, `web/html/src/jest.config.js`).
- Storybook config: `web/.storybook/main.ts`; legacy `.example.ts(x)` files are
  auto-wrapped into generated `.stories.tsx`.

## Build and test commands

CI installs with `npm --prefix web ci --ignore-scripts --save=false` (build CI adds
`--omit=dev`) — mirror those flags. From `web/`:

    npm run build       # build.js; sets NODE_OPTIONS with strict unhandled-rejection flags
    npm run lint        # auto-fixes; also reorders package.json (sort-package-json)
    npm run lint:production   # check-only lint (still may reorder package.json)
    npm run sort-package -- --check
    npm run test        # jest --projects html/src/jest.config.js
    npm run tsc         # tsc --project html/src/tsconfig.json
    npm run all         # build+lint+test+tsc

Dev server for UI work: `npm run proxy https://<server-fqdn>` (from repo root);
Storybook: `npm --prefix web run storybook` (port 6006).

## Gotchas

- Production `build` regenerates license/spec files (`web/spacewalk-web.spec`,
  `web/html/src/vendors/npm.licenses.*`) and fails if left uncommitted.
- Jest: ts-jest `js-with-babel` preset, jsdom, 30s timeout, legacy `jest-jasmine2` runner,
  fixed globals like `serverTimeZone: "Asia/Tokyo"` (`web/html/src/jest.config.js`).
- TypeScript is `strict` but with `noImplicitAny: false` and `allowJs: true`.
- React `^16.14.0` with `@testing-library/react` `12.1.5` — do not assume React 18 APIs.
- RPM packaging does NOT use npm scripts: `web/spacewalk-web.spec` `%build` invokes
  `node html/src/build.js --check-spec=false` directly (same entry point and
  NODE_OPTIONS as `npm run build`; `--check-spec=false` skips the git uncommitted-check
  that cannot work in OBS tarballs). Dependencies come from a pre-packaged
  `node_modules.tar.gz` produced by `npm run zip`; the webpack output is shipped in the
  `spacewalk-html` subpackage under `/usr/share/susemanager/www/htdocs`.

## Pointers

- Full overview incl. dependency workflow: `web/README.md` (its output-dir path is stale,
  see Gotchas above).
- Webpack config/aliases: `web/html/src/build/webpack/config.js`, `web/html/src/build/webpack/alias.js`.
- CI: `.github/workflows/frontend-build.yml`, `frontend-lint.yml`, `frontend-unit-tests.yml`,
  `frontend-typescript-compilation.yml` (all Node 22).
