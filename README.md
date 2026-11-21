# DexForge

**An Android workspace for APK inspection, DEX decompilation, Java editing, rebuilding, file management, and APK signing.**

DexForge brings several Android development and bytecode-editing workflows into one mobile application. It is built for developers, Android enthusiasts, and learners who want to inspect application packages, explore their contents, experiment with code changes, and manage signed output directly from an Android device.

> **Development status:** Actively developed. Features and compatibility may change. Always keep an untouched copy of important APK files before editing.

## Contents

- [Overview](#overview)
- [Features](#features)
- [Typical workflow](#typical-workflow)
- [Technology stack](#technology-stack)
- [Requirements](#requirements)
- [Build from source](#build-from-source)
- [Limitations and compatibility](#limitations-and-compatibility)
- [Security and responsible use](#security-and-responsible-use)
- [Contributing](#contributing)
- [License](#license)

## Overview

DexForge is a native Android application written primarily in Kotlin and built with Jetpack Compose. It combines an APK inspector, a DEX-to-Java editing workflow, a dual-pane file manager, a text/XML editor, and an APK signing studio.

The interface adapts to screen size. On compact screens, users switch between the two file panes; on wider screens, both panes can be displayed side by side.

The project aims to make several technical workflows accessible from one Android workspace instead of requiring a separate utility for every operation.

## Features

### 1. APK inspection

Import an APK through Android's system document picker and open it in the APK inspection workflow. From the inspector, the application provides routes to:

- Open the APK in the DEX editor.
- Explore the APK through the file manager.
- Send the APK to the signing studio.
- Export or share the APK.

### 2. DEX decompilation and Java editor

DexForge integrates JADX components to analyze DEX content and present decompiled Java-like source for browsing and editing.

The editor workflow includes:

- Browsing classes and package folders.
- Searching available DEX content.
- Switching between DEX entries.
- Selecting the available decompiler mode.
- Editing Java source in the editor.
- Undo and redo.
- Compiling edited Java back into DEX.
- Moving between the class browser and code editor.

Decompiled Java is a reconstruction of compiled bytecode. It may differ from the original source, including comments, variable names, formatting, and project organization.

### 3. Java-to-DEX editing workflow

The project includes dependencies for Java compilation and DEX manipulation, including Eclipse JDT Core (ECJ), dexlib2, and Dalvik DX-related tooling.

The intended workflow is:

1. Open an APK or DEX-containing file.
2. Browse to the class you want to examine.
3. Review and edit its decompiled Java representation.
4. Compile the edited source into DEX.
5. Continue through the editor's save/sign workflow.
6. Export or share the resulting output.

Successful compilation depends on the source code, available dependencies, and Android bytecode constraints. Not every decompiled class is guaranteed to compile without additional work.

### 4. Dual-pane file manager

DexForge includes a two-pane workspace for organizing files and working with APK contents.

Available operations include:

- Folder navigation.
- File import using Android's document picker.
- Mounting user-selected folders through the Storage Access Framework (SAF).
- Searching within a pane.
- Selecting multiple entries.
- Copying or moving selected entries to the opposite pane.
- Creating files and folders.
- Renaming and deleting entries.
- Compressing selected entries into ZIP archives.
- Browsing supported archives as folders.
- Exporting and sharing files.

The layout adapts to available screen width, with side-by-side panes on expanded layouts.

### 5. APK signing studio

The signing workspace supports APK signing and keystore profile management.

Its interface provides:

- Selecting an APK from the workspace or importing one.
- Creating a keystore.
- Importing an existing keystore.
- Selecting a keystore profile.
- Choosing the available V1, V2, and V3 signature schemes.
- Signing the selected APK.
- Exporting the signed result.

The project includes Android's apksig library for APK signing operations.

**Signing compatibility matters:** Android normally requires an installed application update to be signed with a compatible certificate. If a modified APK is signed with a different key, it generally cannot be installed as an update over the original app. Keep keystore files and passwords secure.

### 6. Text and XML editor

DexForge provides a dedicated text-file editor screen with actions to modify, save, and close the current document. The application also routes supported decoded XML editing through this editor.

This does not mean every binary Android resource can be edited as plain text. Actual support depends on how the resource is decoded and represented.

### 7. Language and adaptive interface

The application includes an in-app language selector and a dark-themed Jetpack Compose interface. Navigation is available through a bottom navigation bar on compact layouts and a navigation rail on wider layouts.

## Typical workflow

A common APK editing session can follow these steps:

1. **Import** an APK using Android's file picker.
2. **Inspect** the package and choose the relevant action.
3. **Explore** its contents in the file manager or open its DEX editor.
4. **Edit** a class using the Java code editor.
5. **Compile** the changes back into DEX.
6. **Sign** the resulting APK using a keystore in the signing studio.
7. **Export** or share the output.
8. **Test** the result on an appropriate test device.

This is a general workflow, not a guarantee that every APK can be modified successfully. Some applications rely on complex resources, native libraries, integrity checks, custom build systems, or other components that may require additional steps.

## Technology stack

| Technology / library | Purpose |
| --- | --- |
| Kotlin | Main application language |
| Jetpack Compose | Declarative Android UI |
| AndroidX Activity and Lifecycle | Activity integration and lifecycle-aware state |
| Kotlin Coroutines and Flow | Asynchronous work and UI state |
| JADX | DEX analysis and Java decompilation components |
| dexlib2 | DEX file and bytecode support |
| Eclipse JDT Core (ECJ) | Java compilation |
| Android apksig | APK signing |
| Dalvik DX tooling | DEX-related tooling |
| ASM | Bytecode manipulation support |
| AndroidX DocumentFile / SAF | User-selected file and folder access |
| Gradle Kotlin DSL | Build configuration |

Dependency versions and aliases are maintained in the Gradle version catalog at gradle/libs.versions.toml.

## Requirements

### Running the application

- Android 7.0 (API 24) or later.
- Enough free storage for source APKs, working files, and generated output.
- Access to files and folders selected through Android's system document picker.

The current Android application module declares minSdk 24 and targetSdk 36.

### Building from source

- Android Studio with a compatible Android Gradle Plugin environment.
- A JDK compatible with the project's Gradle and Android Gradle Plugin versions.
- Android SDK Platform 36.1, as configured by the app module.
- Network access to download the dependencies declared by the project.

## Build from source

Clone the repository:

~~~bash
git clone https://github.com/elpepe828/DexForge.git
cd DexForge
~~~

Open the project in Android Studio, allow Gradle sync to finish, and make sure the required Android SDK platform is installed.

To build a debug APK from the project root:

~~~bash
./gradlew assembleDebug
~~~

The APK is normally generated at:

~~~text
app/build/outputs/apk/debug/app-debug.apk
~~~

If the Gradle wrapper is not included in your checkout, use the Gradle installation configured by Android Studio or add a wrapper version compatible with the project's Android Gradle Plugin.

## Limitations and compatibility

- **Decompilation is not original source recovery.** Decompiled code is reconstructed from compiled bytecode.
- **Compilation may fail.** Decompiled code can depend on missing classes, libraries, resources, or language features.
- **Rebuilding an APK is more than changing DEX.** Resources, manifests, native libraries, and packaging may also matter.
- **Signature identity matters.** A modified APK signed with a different certificate may not update an existing installation.
- **Compatibility varies.** Android versions, device vendors, APK structure, and app-specific protections can affect results.
- **Keep backups.** Work on copies of important files and retain secure backups of your keystores.

## Security and responsible use

Use DexForge only with applications and files you own or are authorized to inspect and modify. Respect software licenses, copyright, privacy, and applicable laws. Do not use the project to access data without permission or redistribute modified applications without the necessary rights.

Keystore files and signing passwords are sensitive credentials. Do not commit them to the repository, publish them, or include them in screenshots and bug reports.

## Contributing

Bug reports, suggestions, and code contributions are welcome.

When reporting an issue, include:

- DexForge version.
- Android version and device model.
- The operation being performed.
- Expected behavior and actual behavior.
- Relevant logs or a minimal example, only if you have permission to share it.

For code contributions, keep changes focused and explain the purpose of the change in your pull request.

## License

A project license has not been confirmed in the repository files reviewed for this README. Until the owner adds a license, users should not assume that reuse, redistribution, or modification is permitted.
