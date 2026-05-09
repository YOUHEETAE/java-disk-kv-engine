# Contributing to MiniDB — Spatial Page Cache Engine

Thank you for your interest in contributing! Any form of contribution is welcome.

---

## How to Contribute

### Reporting Issues

If you find a bug or have a suggestion, please [open an issue](../../issues) first.  
Include steps to reproduce, expected behavior, and actual behavior if applicable.

### Submitting a Pull Request

1. Fork this repository
2. Create a branch from `dev`
3. Make your changes
4. Open a PR targeting the `dev` branch

---

## Branch Strategy

| Branch | Purpose |
|--------|---------|
| `main` | Stable releases |
| `dev` | Integration branch — base for all PRs |
| `feat/xxx` | New features |
| `fix/xxx` | Bug fixes |
| `docs/xxx` | Documentation updates |

> Please branch off from `dev` and open your PR against `dev`.

---

## What We Welcome

- Bug reports and fixes
- Performance improvements (benchmarks appreciated)
- Documentation improvements
- New spatial index strategies or cache policies
- Test coverage improvements

---

## Getting Started

**Requirements:** Java 21, Maven

```bash
# Build
mvn clean compile

# Run tests
mvn test
```

---

## Commit Messages

Write whatever feels natural — no strict format required.  
Just make it clear what and why.
