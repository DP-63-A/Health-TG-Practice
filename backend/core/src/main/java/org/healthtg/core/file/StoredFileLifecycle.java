package org.healthtg.core.file;

import org.healthtg.core.entry.OwnerContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Persistent protection before an Entry write, including an uncertain/lost acknowledgement. */
@Component
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
public final class StoredFileLifecycle {
    static final String ACTIVE="ACTIVE", PINNED="PINNED", DELETING="DELETING", DELETED="DELETED";
    private final MongoTemplate mongo;

    public StoredFileLifecycle(MongoTemplate mongo) { this.mongo=mongo; }

    static String state(MongoStoredFileDocument file) { return file.lifecycle()==null?ACTIVE:file.lifecycle(); }
    static void requireUsable(MongoStoredFileDocument file) {
        if (!ACTIVE.equals(state(file)) && !PINNED.equals(state(file)))
            throw new StoredFileUnavailableException("File reservation is no longer usable",null);
    }
    private static Criteria active() {
        return new Criteria().orOperator(Criteria.where("lifecycle").is(null),Criteria.where("lifecycle").is(ACTIVE));
    }
    private static Criteria owned(OwnerContext owner,UUID id) {
        return Criteria.where("_id").is(id.toString()).and("ownerId").is(owner.userId().toString());
    }
    public void pinForEntry(OwnerContext owner,UUID id) {
        var query=Query.query(new Criteria().andOperator(owned(owner,id),
                new Criteria().orOperator(active(),Criteria.where("lifecycle").is(PINNED))));
        var changed=mongo.findAndModify(query,new Update().set("lifecycle",PINNED).inc("version",1),
                FindAndModifyOptions.options().returnNew(true),MongoStoredFileDocument.class);
        if (changed==null) throw new StoredFileNotFoundException();
        // Never unpin on an Entry write failure: that write may have succeeded in MongoDB.
    }
    MongoStoredFileDocument bind(OwnerContext owner,UUID id,UUID entryId) {
        var query=Query.query(new Criteria().andOperator(owned(owner,id),
                Criteria.where("lifecycle").is(PINNED),new Criteria().orOperator(
                        Criteria.where("entryId").is(null),Criteria.where("entryId").is(entryId.toString()))));
        var changed=mongo.findAndModify(query,new Update().set("entryId",entryId.toString()).inc("version",1),
                FindAndModifyOptions.options().returnNew(true),MongoStoredFileDocument.class);
        if(changed==null) throw new FileValidationException("File binding is unavailable");
        return changed;
    }
    boolean beginDeletion(OwnerContext owner,UUID id) {
        var query=Query.query(new Criteria().andOperator(owned(owner,id),active(),Criteria.where("entryId").is(null)));
        return mongo.findAndModify(query,new Update().set("lifecycle",DELETING).inc("version",1),
                FindAndModifyOptions.options().returnNew(true),MongoStoredFileDocument.class)!=null;
    }
    void finishDeletion(OwnerContext owner,UUID id) {
        var query=Query.query(new Criteria().andOperator(owned(owner,id),Criteria.where("lifecycle").is(DELETING),
                Criteria.where("entryId").is(null)));
        var update=new Update().set("lifecycle",DELETED).inc("version",1);
        for(String field:new String[]{"relativePath","mediaType","extension","size","width","height","sha256","createdAt","entryId"})
            update.unset(field);
        if(mongo.updateFirst(query,update,MongoStoredFileDocument.class).getMatchedCount()!=1)
            throw new StoredFileUnavailableException("File deletion could not be acknowledged",null);
    }
}
