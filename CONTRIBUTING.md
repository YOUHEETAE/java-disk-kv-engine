# Contributing to MiniDB — Spatial Page Cache Engine

Thank you for your interest in contributing! Any form of contribution is welcome.

---

## How to Contribute

### Reporting Issues

If you find a bug or have a suggestion, please [open an issue](../../issues) first.  
Include steps to reproduce, expected behavior, and actual behavior if applicable.

### Submitting a Pull Request

1. Fork this repository
2. Create a branch from `main`
3. Make your changes
4. Open a PR targeting the `main` branch

---

## Branch Strategy

Trunk-based. `main` is the only long-lived branch and always holds the latest
working state — every merge lands with the full test suite passing.

| Branch | Purpose |
|--------|---------|
| `main` | The trunk — base for all PRs |
| `feat/xxx` | New features |
| `fix/xxx` | Bug fixes |
| `refactor/xxx` | Refactoring |
| `docs/xxx` | Documentation updates |

> Branch off from `main` and open your PR against `main`.

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

The Maven module lives in `geo-index/`, not at the repository root.

```bash
# Build
mvn -f geo-index/pom.xml clean compile

# Run tests
mvn -f geo-index/pom.xml test
```

---

## Commit Messages

Write whatever feels natural — no strict format required.  
Just make it clear what and why.
