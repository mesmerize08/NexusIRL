> **SUPERSEDED (2026-10-19):** this spec is replaced by [docs/PORT_PLAN.md](docs/PORT_PLAN.md), which is based on the reverse-engineered Moblin architecture. See PORT_PLAN.md §2 for what changed and why. Kept for history only.

# Technical Specification & Execution Plan: Moblin Android Port (Project "Moblin-A")

**Role:** Lead Android Systems Architect / Senior Mobile Systems Engineer  
**Objective:** Replicate the ultra-low latency, professional-grade streaming capabilities of Moblin (iOS) for the Android ecosystem.  
**Primary Goal:** Sub-100ms latency for SRT/RTMP streaming with manual hardware camera control.

---

## 1. Architectural Foundation & Core Engine

### 1.1 Media Pipeline (NDK Layer)
To match iOS performance, the media engine must bypass the JVM for packetization.

- **Protocols:**
  - **SRT:** Native `libsrt` (C++) integration via NDK.
  - **RTMP:** FFmpeg 6.0+ compiled with `librtmp` and `openssl`.

- **Hardware Acceleration:**  
  Mandatory use of `MediaCodec` with:
  - `KEY_LATENCY = 0` (Zero-latency mode)  
  - `KEY_PRIORITY = 1` (Real-time priority)

---

### 1.2 Camera & Sensors

- **API:** Camera2 API (Native/Java bridge)  
  Use `CaptureRequest` for:
  - Manual ISO  
  - Focus distance  
  - Shutter speed  

- **Thermal Monitoring:**  
  Implement `PowerManager.getThermalHeadroom` to trigger dynamic bitrate/FPS downscaling before thermal throttling occurs.

---

### 1.3 Audio Engine

- **Library:** Google **Oboe** (C++)

- **Logic:**  
  A custom C++ mixer to handle raw PCM buffers from:
  - Internal microphones  
  - USB-C microphones  

  Target: **sub-10ms round-trip latency**

---

## 2. Project Structure (Clean Architecture / MVVM)

```text
root/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── cpp/                 # Native C++ Logic (Oboe, libsrt, FFmpeg)
│   │   │   │   ├── mixer/           # Low-latency Audio Mixer
│   │   │   │   ├── streaming/       # Protocol implementations
│   │   │   │   └── CMakeLists.txt
│   │   │   ├── java/com/moblin/
│   │   │   │   ├── core/            # Permissions, Battery, Thermal logic
│   │   │   │   ├── data/            # Local Storage (DataStore, Room)
│   │   │   │   ├── domain/          # Interfaces and UseCases
│   │   │   │   │   ├── model/       # Kotlin Entities
│   │   │   │   │   └── repository/  # Interface Protocols
│   │   │   │   ├── ui/              # Jetpack Compose Screens/ViewModels
│   │   │   │   └── service/         # Foreground Service (Streaming process)
│   │   │   └── res/                 # UI Resources
└── build.gradle.kts                 # Dependency Version Catalog
```

---

## 3. Tech Stack & Dependency Mapping

| iOS Framework              | Android Equivalent                  | Library/Tool                |
|--------------------------|------------------------------------|-----------------------------|
| AVFoundation             | Camera2 + Oboe                     | Google Oboe / Camera2 API   |
| Metal                    | OpenGL ES 3.2                      | GLSurfaceView / EGL Context |
| Combine / Swift Concurrency | Coroutines + Flow              | Kotlin Coroutines           |
| SwiftUI                  | Jetpack Compose                    | Compose 1.5+                |
| UserDefaults             | Jetpack DataStore                  | DataStore Preferences       |
| CoreMotion               | SensorManager                      | Android Sensor Framework    |

---

## 4. Core Data Models (Kotlin)

```kotlin
data class StreamProfile(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val endpointUrl: String,
    val protocol: ProtocolType, // SRT, RTMP
    val bitrate: Int,           // bps
    val resolution: Size,       // e.g., 1920x1080
    val fps: Int,
    val srtLatency: Int         // ms
)

data class CameraCaptureState(
    val iso: Int,
    val shutterSpeed: Long,
    val whiteBalance: Int,
    val thermalLevel: Int
)
```

---

## 5. Interface Protocols

```kotlin
interface StreamEngine {
    fun start(profile: StreamProfile)
    fun stop()
    fun getStreamMetrics(): Flow<StreamStats>
}

interface AudioMixer {
    fun setInputGain(volume: Float)
    fun toggleSource(sourceId: String, enabled: Boolean)
}
```

---

## 6. Development Constraints & Coding Standards

- **Viewfinder Performance:**  
  Never use `TextureView`. Use `SurfaceView` for direct-to-display rendering.

- **Memory Safety:**  
  Enforce RAII (Resource Acquisition Is Initialization) in C++.

- **Threading:**  
  - High-frequency JNI calls must run on:
    - `HandlerThread` or  
    - `Dispatchers.Default`  
  - Never run media/audio work on the UI thread.

- **UI/UX:**  
  Implement “swipe-to-hide” HUD using:
  - Compose `Draggable` or  
  - `Swipeable` modifiers  

- **Testing:**  
  - Minimum **80% coverage** on Domain and Data layers  
  - Unit tests for all profile serialization  

---

## 7. Step-by-Step Build & Execution Plan (Claude Code Autonomous Workflow)

### Phase 1: Project Bootstrap

1. Initialize new Android project:
   - Language: Kotlin
   - Min SDK: 26+
   - Enable C++ (NDK + CMake)

2. Configure Gradle:
   - Enable Version Catalog
   - Add dependencies:
     - Kotlin Coroutines
     - Jetpack Compose
     - DataStore
     - Camera2
     - Oboe (via CMake)

3. Create base package structure:
   ```
   com.moblin.core
   com.moblin.data
   com.moblin.domain
   com.moblin.ui
   com.moblin.service
   ```

---

### Phase 2: Native Layer Setup (Critical Path)

4. Configure CMake:
   - Add modules:
     - libsrt
     - FFmpeg (custom build)
     - Oboe

5. Compile dependencies for:
   - ABI: `arm64-v8a`

6. Create JNI bridge:
   - Functions:
     - `startStream()`
     - `stopStream()`
     - `pushVideoFrame()`
     - `pushAudioFrame()`

---

### Phase 3: Streaming Engine

7. Implement `StreamEngine`:
   - Kotlin interface + JNI-backed implementation
   - Support:
     - SRT socket handling
     - RTMP push via FFmpeg

8. Implement adaptive bitrate logic:
   - Input:
     - Thermal headroom
     - Dropped frames
   - Output:
     - Dynamic bitrate + FPS adjustment

---

### Phase 4: Camera System

9. Implement Camera2 controller:
   - Manual exposure control
   - ISO locking
   - Focus distance control

10. Bind camera output to:
   - SurfaceView (preview)
   - OpenGL input surface

---

### Phase 5: OpenGL Rendering Pipeline

11. Create EGL context:
   - Offscreen rendering surface

12. Implement renderer:
   - Inputs:
     - Camera frames
   - Overlays:
     - HUD
     - Bitrate display

13. Output:
   - MediaCodec input surface

---

### Phase 6: Encoding Pipeline

14. Configure MediaCodec:
   - H.264 (baseline/high)
   - Zero-latency flags

15. Pipe frames:
   ```
   Camera → GL → MediaCodec → Native Stream Engine
   ```

---

### Phase 7: Audio Engine (Oboe)

16. Initialize Oboe stream:
   - Low latency mode
   - Exclusive mode if supported

17. Build audio mixer:
   - Combine:
     - Internal mic
     - USB input

18. Sync audio with video timestamps

---

### Phase 8: Data Layer

19. Implement DataStore:
   - Persist:
     - Stream profiles
     - User settings

20. Add serialization tests

---

### Phase 9: UI Layer (Compose)

21. Build main screen:
   - Camera preview (SurfaceView host)
   - HUD overlay

22. Add controls:
   - Start/Stop stream
   - Bitrate display
   - Audio levels

23. Implement gesture system:
   - Swipe-to-hide HUD

---

### Phase 10: Background Streaming Service

24. Implement Foreground Service:
   - Keeps stream alive in background
   - Handles lifecycle

---

### Phase 11: External Integrations

25. Integrate WebSockets:
   - Twitch
   - YouTube
   - Kick

26. Display chat overlay (optional toggle)

---

### Phase 12: Performance Optimization

27. Integrate Perfetto tracing:
   - Measure:
     - Frame time
     - Encoder latency
     - Network latency

28. Optimize:
   - Eliminate frame drops
   - Maintain <100ms latency

---

### Phase 13: Testing & Validation

29. Unit Tests:
   - Domain layer
   - Data serialization

30. Integration Tests:
   - Streaming stability
   - Audio/video sync

31. Field Testing:
   - Multiple Android devices
   - Different thermal conditions

---

## 8. Definition of Done

- Sub-100ms latency achieved (SRT target)
- Stable 1080p streaming at 30/60 FPS
- No frame drops under sustained load
- Audio latency <10ms
- Manual camera controls fully functional
- App stable under thermal stress
- 80%+ test coverage (domain/data layers)

---

## 9. Execution Notes for Claude Code

- Prioritize native performance over abstraction
- Keep hot paths in C++
- Avoid unnecessary Kotlin-layer processing in media pipeline
- Validate each phase before proceeding
- Log key metrics:
  - Bitrate
  - Frame time
  - Dropped frames
  - Thermal headroom

---

**End of Specification**