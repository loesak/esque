package org.loesak.esque.core.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.loesak.esque.core.yaml.model.MigrationFile;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class MigrationFileLoader {

    private static final String MIGRATION_DEFINITION_DIRECTORY = "es.migration";
    private static final String MIGRATION_DEFINITION_FILE_NAME_REGEX = "^V((\\d+\\.?)+)__(\\w+)\\.yml$";
    private static final Pattern MIGRATION_DEFINITION_FILE_NAME_PATTERN = Pattern.compile(MIGRATION_DEFINITION_FILE_NAME_REGEX);

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final MessageDigest MESSAGE_DIGEST;

    static {
        try {
            MESSAGE_DIGEST = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("failed to create message digest");
        }
    }

    public List<MigrationFile> load() throws Exception {
        log.info("Loading migration files from [{}]", MIGRATION_DEFINITION_DIRECTORY);

        final List<MigrationFile> files = Files
                .list(Paths.get(Objects.requireNonNull(this.getClass().getClassLoader().getResource(MIGRATION_DEFINITION_DIRECTORY + "/")).toURI()))
                .filter(Files::isRegularFile)
                .filter(path -> path.toFile().getName().matches(MIGRATION_DEFINITION_FILE_NAME_REGEX))
                .map(MigrationFileLoader::read)
                .sorted()
                .toList();

        log.info("Found [{}] migration files", files.size());

        return files;
    }

    private static MigrationFile read(final Path path) {
        String filename = path.toFile().getName();

        log.info("Reading contents of migration file [{}]", filename);

        try {
            Matcher matcher = MIGRATION_DEFINITION_FILE_NAME_PATTERN.matcher(filename);
            if (!matcher.matches()) {
                throw new IllegalStateException("filename does not match expected pattern");
            }

            return new MigrationFile(
                    new MigrationFile.MigrationFileMetadata(
                            filename,
                            matcher.group(1),
                            matcher.group(3),
                            MigrationFileLoader.calculateChecksum(path)),
                    YAML_MAPPER.readValue(Files.newInputStream(path), MigrationFile.MigrationFileContents.class));
        } catch (Exception e) {
            throw new RuntimeException(String.format("failed to read the contents of migration file [%s]", filename), e);
        }
    }

    private static Integer calculateChecksum(Path path) throws Exception {
        MigrationFileLoader.MESSAGE_DIGEST.reset();
        MigrationFileLoader.MESSAGE_DIGEST.update(Files.readAllBytes(path));

        return ByteBuffer.wrap(MigrationFileLoader.MESSAGE_DIGEST.digest()).getInt();
    }
}
