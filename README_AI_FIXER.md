# AI Build Fixer - Autonomous Agent System

## 🎯 Overview

The AI Build Fixer is a hyper-intelligent autonomous agent that prevents GitHub Action build failures by:
- Monitoring build logs in real-time
- Understanding compiler/runtime errors
- Applying fixes directly to the repository
- Committing/pushing patches automatically
- Re-triggering builds
- Learning from each fix

## 🚀 Quick Start

### Manual Activation

```bash
# Monitor latest workflows automatically
./scripts/activate-ai-fixer.sh

# Analyze specific run
./scripts/activate-ai-fixer.sh <RUN_ID>
```

### Automatic Activation

The agent automatically activates when:
- A GitHub Action workflow fails (via `ai-auto-fix.yml` workflow)
- A push occurs on main branch
- A release is created
- Manual dispatch is triggered

## 📁 System Components

### Core Files

- **`agents/self_build_fixer.mdc`**: Agent definition and rules
- **`scripts/ai-build-fixer.sh`**: Main AI fixer script
- **`scripts/activate-ai-fixer.sh`**: Activation wrapper
- **`.cursor/ai_learning/patterns.json`**: Error pattern database
- **`.cursor/ai_learning/knowledge.md`**: Knowledge base
- **`.github/workflows/ai-auto-fix.yml`**: Auto-activation workflow

### Learning Database

Located in `.cursor/ai_learning/`:
- **`patterns.json`**: 10+ pre-loaded error patterns with fixes
- **`knowledge.md`**: Comprehensive error classification guide
- **`README.md`**: Learning system documentation

## 🔧 Error Categories Supported

1. **CMake/NDK Toolchain**: march flag issues, toolchain configuration
2. **Kotlin/Compose**: JVM default, version compatibility
3. **Gradle Dependencies**: Resolution conflicts, version mismatches
4. **KSP Plugin**: Version alignment with Kotlin
5. **Build Artifacts**: Library not found, path issues
6. **BoringSSL**: Build failures, library linking
7. **And more...**

## 📊 How It Works

### 1. Detection
```
[AI-MVC] Monitoring workflow...
[AI-MVC] Build failure detected!
```

### 2. Analysis
```
[AI-MVC] Analyzing failure...
[AI-MVC] Pattern matched: clang: error.*unsupported.*march
[AI-MVC] Confidence: 95%
```

### 3. Fix Application
```
[AI-MVC] Root Cause: CMake march flag issue
[AI-MVC] Attempt: #1
[AI-MVC] Applying patch...
```

### 4. Commit & Push
```
[AI-MVC] Committing changes...
[AI-MVC] Pushing changes...
✅ Changes pushed, rebuild triggered
```

### 5. Learning
After success, patterns are updated with:
- Success rate
- Confidence adjustment
- New generalizations

## 🛠️ Fix Strategies

### Escalation Levels

1. **Level 1**: Minimal targeted fix (single file, single change)
2. **Level 2**: Multi-file coordination (version updates)
3. **Level 3**: Configuration restructuring (gradle.properties)
4. **Level 4**: Fallback mechanisms (BoringSSL replacement)

### Safety Rules

✅ Always test minimal changes first  
✅ Never delete critical modules  
✅ Never disable security hardening  
✅ Never downgrade blindly  
✅ Always verify compatibility matrix  

## 📈 Monitoring

### Terminal Output

The agent provides real-time progress logging:

```
[AI-MVC] Thinking...
[AI-MVC] Analyzing logs...
[AI-MVC] Found error pattern...
[AI-MVC] Patch generated.
[AI-MVC] Committing changes...
[AI-MVC] Triggering rebuild...
[AI-MVC] Observing pipeline...
```

### Build Stages Detected

- Environment setup
- Dependency resolution
- Kotlin compilation
- KSP processing
- CMake toolchain detection
- NDK packaging
- Artifact signing

## 🔄 Loop Behavior

The agent **NEVER stops** if the build is failing. It continues until:
- ✅ A successful build is achieved
- ❌ Maximum attempts reached (default: 10)

## 🎓 Self-Improvement

After each successful fix:

1. **Pattern Update**: Success rate tracked in `patterns.json`
2. **Knowledge Base**: New patterns documented in `knowledge.md`
3. **Agent Evolution**: Logic updated in `agents/self_build_fixer.mdc`
4. **PR Creation**: Self-improvement PRs created for major learnings

## 🔍 Troubleshooting

### Script won't run

```bash
# Check permissions
chmod +x scripts/ai-build-fixer.sh
chmod +x scripts/activate-ai-fixer.sh

# Check dependencies
gh --version  # GitHub CLI
jq --version  # JSON processor (recommended)
```

### No patterns matched

- Check `patterns.json` for relevant patterns
- Add new patterns based on error logs
- Review `knowledge.md` for fix strategies

### Commit/Push fails

- Ensure GitHub CLI is authenticated: `gh auth login`
- Check repository permissions
- Verify git is configured correctly

## 📝 Example Output

```
╔════════════════════════════════════════════════════════════════╗
║         🤖 AI BUILD FIXER - Autonomous Agent 🤖             ║
║         Version 1.0.0 - Learning Enabled                      ║
╚════════════════════════════════════════════════════════════════╝

[AI-MVC] Monitoring workflow...
Run ID: 19119910885
Status: completed
Conclusion: failure

❌ BUILD FAILURE DETECTED!

[AI-MVC] Analyzing failure...
❌ Failed Job: Build Xray-core with BoringSSL / Build BoringSSL (arm64-v8a)
❌ Failed Step: Build BoringSSL
📋 Job ID: 54637888941

[AI-MVC] Fetching error logs...
✅ Pattern matched!

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[AI-MVC] Root Cause: Build BoringSSL
[AI-MVC] Attempt: #1
[AI-MVC] Confidence: 90%
[AI-MVC] Context: build/artifacts
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

[AI-MVC] Applying patch...
→ Applying artifact fix...

[AI-MVC] Committing changes...
✅ Changes committed
[AI-MVC] Pushing changes...
✅ Changes pushed, rebuild triggered
```

## 🎯 Long-Term Goals

- **Predictive**: Predict build failures before CI runs
- **Proactive**: Patch proactively before failures occur
- **Auto-Upgrade**: Responsibly upgrade dependency trees
- **Zero-Downtime**: Reduce time to green builds to near-zero

## 📚 Additional Resources

- **Agent Definition**: `agents/self_build_fixer.mdc`
- **Learning Database**: `.cursor/ai_learning/`
- **Monitoring Scripts**: `scripts/hyper-monitor.sh`, `scripts/hyper-auto-fix.sh`

---

**Status**: ✅ Active and Ready  
**Version**: 1.0.0  
**Last Updated**: 2024-01-01
