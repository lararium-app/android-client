# 📚 Documentation Guide

## Quick Navigation

### 🚀 I want to **use this app**
→ Start with [README.md](README.md)

### 🛠️ I want to **understand the code**
→ Read [ARCHITECTURE.md](ARCHITECTURE.md)

### 🐛 I want to **report a bug or request a feature**
→ Open an [issue](../../issues)

---

## File Directory

| File | Purpose |
|------|---------|
| **README.md** | Full documentation - start here! |
| **ARCHITECTURE.md** | Technical design and code structure |
| **LOCAL_AI_SETUP.md** | On-device models and native library build |

---

## Quick Start

```bash
# Clone and build (cloud-only)
git clone https://github.com/mx37/grapheneos-ai.git
cd grapheneos-ai
./gradlew assembleDebug

# With local AI support (requires NDK)
./scripts/build-llama-android.sh --download-ndk
./gradlew assembleDebug

# Release
./gradlew assembleRelease
```

---

📖 **Questions?** Open an [issue](../../issues) or [discussion](../../discussions).
