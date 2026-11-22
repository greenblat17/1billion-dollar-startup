# DEV deployment from a release ID

**Build Speaky release** runs from `main`. Select `main` in the **Run workflow**
menu and pass the full `source_sha` of the branch you want to build. The build
summary gives the run ID. The build also runs after a push to `main`.

**Redeploy DEV (release ID)** runs from `main`. Enter that ID as
`release_run_id`. Use `components=all` for a Foundation release, or pass a
comma-separated list of `ktor`, `ai`, and `miniapp`.

## Activate DEV

1. Confirm the active DEV runtime family and the Mini App HTTPS address.
2. Restrict the `deploy-dev-release` environment to `main`. Configure reviewers to
   approve the exact candidate that will run with access to DEV services.
3. In `deploy-dev-release`, put the verified host keys in
   `SPEAKY_RELEASE_KNOWN_HOSTS` under `speaky-dev-cmp` and `speaky-dev-ai`.
   During the parallel rollout the job can reuse the existing repository
   `DEV_CMP_SERVER_*` and `DEV_AI_SERVER_*` SSH secrets. Once scoped
   credentials are available, set `SPEAKY_RELEASE_KTOR_*` and
   `SPEAKY_RELEASE_AI_*` there. The job uses the scoped values first.
4. Set environment variables `SPEAKY_RELEASE_RUNTIME_FAMILY` and
   `SPEAKY_RELEASE_MINIAPP_URL`. Use the public HTTPS URL ending in `/miniapp`.
   Set `SPEAKY_RELEASE_ENABLED=true` after the secrets and protections are ready.
5. Wait for any legacy DEV deployment to finish. Start the first release-ID
   deployment in its own window. The old workflow uses its own environment and locks.
6. Verify the image IDs and readiness in the workflow summary. Retire the old
   DEV entry point and update the BB caller after validating the new route.

The existing **Redeploy DEV** and **Redeploy PROD** keep their current inputs.
The release-ID workflow reaches only DEV. PROD promotion requires a separate
change and verification on a DEV runtime in the same family.
