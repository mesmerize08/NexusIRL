# Pre-release audit

In November 2026, before v0.5.0, NexusIRL had a release-readiness audit of commit `b9eee46`
(shortly after v0.4.0). The audit was produced by OpenAI's ChatGPT, a different AI system from
the one that writes most of NexusIRL's code (Anthropic's Claude), as a second opinion. It is not
a human security audit.

- [RELEASE_READINESS_AUDIT.md](RELEASE_READINESS_AUDIT.md): the audit as received. Its verdict
  ("not ready") applies to that commit.
- [AUDIT_REVIEW.md](AUDIT_REVIEW.md): the project's review of the audit (written with Claude),
  with each finding traced in the code, plus the commits that fixed them. All findings were fixed in v0.5.0 except one
  low-severity item that matches upstream Moblin.

Found something the audit missed? Report security problems privately as described in
[SECURITY.md](../../SECURITY.md).
