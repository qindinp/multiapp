# Project Notes

- The machine already has Gradle wrapper distributions cached under `C:\Users\Administrator\.gradle\wrapper\dists`.
- If `.\gradlew.bat` prints `Downloading ...`, do not assume Gradle is missing. First check `gradle\wrapper\gradle-wrapper.properties` and the matching cache directory/hash under `C:\Users\Administrator\.gradle\wrapper\dists`.
- Prefer diagnosing wrapper cache mismatches or incomplete downloads before waiting for a fresh Gradle download.
- Respond to the user in Simplified Chinese by default unless the user explicitly asks for another language.
- Keep code, commands, file paths, logs, error messages, API names, and identifiers in their original language.
