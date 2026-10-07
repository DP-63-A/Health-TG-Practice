package org.healthtg.core.file;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("stored_files")
@CompoundIndex(name = "file_owner_entry_idx", def = "{'ownerId': 1, 'entryId': 1}")
record MongoStoredFileDocument(@Id String id, String ownerId, String entryId, String relativePath,
                               String mediaType, String extension, Long size, Integer width, Integer height,
                               String sha256, Instant createdAt, @Version Long version, String lifecycle) {
    @PersistenceCreator
    MongoStoredFileDocument { }

    MongoStoredFileDocument(String id,String ownerId,String entryId,String relativePath,String mediaType,
                            String extension,long size,int width,int height,String sha256,Instant createdAt,Long version) {
        this(id,ownerId,entryId,relativePath,mediaType,extension,size,width,height,sha256,createdAt,version,null);
    }
}
