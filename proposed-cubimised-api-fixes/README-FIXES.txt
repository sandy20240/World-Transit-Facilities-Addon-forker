Cubimised API rendering fixes (proposed source replacements)

Files:
- CubimisedTerrainRenderer.java
- CubimisedGpuBuffer.java
- CubimisedChunkRebuildSystem.java

These are staged as a proposal bundle in a separate directory, not wired into this
repository's build. Copy/review them against the actual Cubimised API source tree.

Changes:
- Tracks section meshes with a collision-safe (chunk key, section Y) record.
- Clears all section meshes and coverage state when a chunk unloads.
- Marks chunk coverage complete only after all sections rebuild successfully.
- Accounts active uploaded vertex bytes when replacing/removing meshes.
- Makes rebuild budget count individual sections rather than whole vertical chunks.
- Clears pending rebuild work when the client world changes or unloads.
- Unbinds GPU buffers in finally blocks.

Important: these files have not been built or run in Minecraft here. Review mappings/API
compatibility and run ./gradlew build in the target project before merging.
