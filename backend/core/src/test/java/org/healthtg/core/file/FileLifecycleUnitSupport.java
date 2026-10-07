package org.healthtg.core.file;

import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.mockito.Mockito.*;

/** Mongo boundary stub for existing disk unit tests; lifecycle and OS guard remain production code. */
final class FileLifecycleUnitSupport {
    private FileLifecycleUnitSupport() { }
    static StoredFileLifecycle lifecycle(Map<String, MongoStoredFileDocument> database) {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.findAndModify(any(Query.class),any(Update.class),any(FindAndModifyOptions.class),eq(MongoStoredFileDocument.class)))
                .thenAnswer(call -> {
                    Query query=call.getArgument(0);
                    var criteria=(org.bson.Document)((java.util.List<?>)query.getQueryObject().get("$and")).getFirst();
                    String id=criteria.getString("_id");
                    var old=database.get(id);
                    if(old==null || !old.ownerId().equals(criteria.getString("ownerId"))) return null;
                    Update update=call.getArgument(1);
                    var set=(org.bson.Document)update.getUpdateObject().get("$set");
                    var changed=new MongoStoredFileDocument(old.id(),old.ownerId(),
                            set.containsKey("entryId")?set.getString("entryId"):old.entryId(),old.relativePath(),
                            old.mediaType(),old.extension(),old.size(),old.width(),old.height(),old.sha256(),old.createdAt(),
                            old.version()==null?1:old.version()+1,set.containsKey("lifecycle")?set.getString("lifecycle"):old.lifecycle());
                    database.put(id,changed);
                    FindAndModifyOptions options=call.getArgument(2);
                    return options.isReturnNew()?changed:old;
                });
        return new StoredFileLifecycle(mongo);
    }
}
