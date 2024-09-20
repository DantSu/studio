package studio.core.v1.writer;

import java.io.IOException;
import java.nio.file.Path;

import studio.core.v1.model.StoryPack;
import studio.metadata.DatabasePackMetadata;
import java.util.Optional;

public interface StoryPackWriter {

    void write(StoryPack pack, Path path, boolean enriched, Optional<DatabasePackMetadata> metadata) throws IOException;

}
