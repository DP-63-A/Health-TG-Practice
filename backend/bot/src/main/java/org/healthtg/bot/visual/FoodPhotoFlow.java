package org.healthtg.bot.visual;

import org.healthtg.bot.*;
import org.healthtg.bot.datetime.DateTimePicker;
import org.healthtg.bot.recognition.*;
import org.healthtg.bot.draft.DraftReviewFlow;
import org.healthtg.bot.correction.*;
import org.healthtg.core.dialog.*;
import org.healthtg.core.entry.*;
import org.healthtg.core.file.FileStorageService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Persisted candidate before Entry; external side effects have explicit recovery phases. */
public final class FoodPhotoFlow {
    @FunctionalInterface public interface ImageLoader { byte[] load(String fileId) throws RecognitionException; }
    private final EntryCoreService entries;
    private final DialogStateService dialogs;
    private final FileStorageService files;
    private final FoodRecognitionService recognition;
    private final ImageLoader loader;
    private final DraftReviewFlow drafts;
    private final String mode;
    private boolean chooseImageClass;
    /** Production asks before the model call; old programmatic food callers keep their contract. */
    public FoodPhotoFlow withImageClassSelection() { chooseImageClass=true; return this; }
    public FoodPhotoFlow(EntryCoreService entries, DialogStateService dialogs, FileStorageService files,
                         FoodRecognitionService recognition, ImageLoader loader, DraftReviewFlow drafts, String mode) {
        this.entries=entries; this.dialogs=dialogs; this.files=files; this.recognition=recognition;
        this.loader=loader; this.drafts=drafts; this.mode=mode;
    }
    public Optional<List<BotAction>> guard(BotUpdate u, OwnerContext owner) {
        return pending(owner).map(s -> valid(s) ? prompt(u,s,"Сначала завершите или отмените ввод фотографии.") : invalid(u,owner));
    }
    public boolean hasPending(OwnerContext owner) { return pending(owner).isPresent(); }
    /** A terminal Entry may have been confirmed in either interface; resume only on a new update. */
    public Optional<List<BotAction>> resume(BotUpdate u, OwnerContext owner, ZoneId zone) {
        var cancellation=resumeCancellation(u,owner,zone);
        if(cancellation.isPresent()) return cancellation;
        var found=dialogs.find(owner).filter(s -> s.context().containsKey("photo_queue"));
        if(found.isEmpty()) return Optional.empty();
        var state=found.get();
        var queued=validatedQueue(owner,state);
        if(queued.isEmpty()) return Optional.of(damagedQueue(u));
        var data=queued.get().data();
        Entry entry=queued.get().entry();
        if(entry.status()==EntryStatus.DRAFT) return Optional.empty();
        if(u.updateId()<observedUpdate(state)) return Optional.empty();
        int next=Math.toIntExact(number(data,"candidate_index"))+1;
        var candidates=candidates(data);
        if(next>=candidates.size()) {
            finish(owner,u);
            return Optional.of(response(u,"Все показатели фотографии обработаны. Подтверждённые записи сохранены.",List.of()));
        }
        selectCandidate(data,next);
        return Optional.of(prompt(u,save(owner,null,"food_clarify",data,u,"next"),
                "Предыдущий показатель завершён. Проверьте следующий."));
    }
    private Optional<DialogState> pending(OwnerContext owner) {
        return dialogs.find(owner).filter(s -> s.step().startsWith("food_"));
    }
    public List<BotAction> message(BotUpdate u, OwnerContext owner, ZoneId zone) {
        var cancellation=resumeCancellation(u,owner,zone);
        if(cancellation.isPresent()) return cancellation.get();
        var found=pending(owner);
        if(found.isPresent()) {
            var s=found.get(); if (!valid(s)) return invalid(u,owner);
            if(u.image()!=null || u.updateId()<=last(s) || !s.step().equals("food_clarify"))
                return prompt(u,s,"Продолжите текущий ввод.");
            Map<String,Object> data=new LinkedHashMap<>(s.context());
            String input=u.text()==null?"":u.text().strip();
            if(input.isEmpty() || input.codePointCount(0,input.length())>2000) return prompt(u,s,"Введите короткий ответ.");
            var payload=map(data.get("payload")); var origins=origins(data);
            String field=expected(data);
            try {
                switch(field) {
                    case "image_class" -> {
                        String kind=switch(input.toLowerCase(Locale.ROOT)) {
                            case "еда", "food_photo" -> "food_photo";
                            case "здоровье", "скриншот", "health_screenshot" -> "health_screenshot";
                            case "часы", "watch_photo" -> "watch_photo";
                            default -> throw new IllegalArgumentException();
                        };
                        data.put("image_class",kind); data.put("manual_class",false);
                        if(!kind.equals("food_photo")) {
                            payload.clear(); origins.clear(); data.put("candidate_index",0); data.put("candidates",List.of(Map.of()));
                        }
                    }
                    case "code" -> {
                        String code=switch(input.toLowerCase(Locale.ROOT)) {
                            case "шаги", "steps" -> "steps"; case "сон", "sleep_duration_min" -> "sleep_duration_min";
                            case "пульс", "heart_rate" -> "heart_rate"; default -> throw new IllegalArgumentException();
                        }; payload.put("code",code); origins.put("code","reported");
                        data.put("candidate_index",0); data.put("candidates",List.of(Map.of()));
                    }
                    case "value" -> {
                        var parsed=new CorrectionInputParser().parseNumber(input);
                        if(!(parsed instanceof CorrectionResult.Success<?> success)) throw new IllegalArgumentException();
                        checkedNumber(success.value()); payload.put("value",success.value().toString()); origins.put("value","reported");
                    }
                    case "unit" -> {
                        var normalized=MetricCandidate.normalize((String)payload.get("code"),
                                payload.get("value")==null?null:new BigDecimal(payload.get("value").toString()),input);
                        if(normalized.unit()==null) throw new IllegalArgumentException();
                        payload.put("unit",normalized.unit()); origins.put("unit",input.equals(normalized.unit())?"reported":"computed");
                        if(normalized.value()!=null) payload.put("value",normalized.value().toPlainString());
                        if(normalized.converted()) origins.put("value","computed");
                        data.put("reported_unit",input);
                    }
                    case "metric_date" -> {
                        var parsed=new CorrectionInputParser().parseDate(input);
                        if(!(parsed instanceof CorrectionResult.Success<?> success)) throw new IllegalArgumentException();
                        payload.put("local_date",success.value().toString()); origins.put("local_date","reported");
                    }
                    case "description" -> { payload.put("description",input); origins.put("description","reported"); }
                    case "date" -> {
                        var parsed=new CorrectionInputParser().parseDate(input);
                        if(!(parsed instanceof CorrectionResult.Success<?> success)) throw new IllegalArgumentException();
                        data.put("date",success.value().toString()); origins.put("local_date","reported");
                    }
                    case "time" -> {
                        if(!input.matches("[0-9]{2}:[0-9]{2}")) throw new IllegalArgumentException();
                        data.put("time",LocalTime.parse(input).toString()); origins.put("local_time","reported");
                    }
                    case "mass" -> {
                        if(!input.equalsIgnoreCase("не знаю")) {
                            var parsed = new CorrectionInputParser().parseNumber(input);
                            if (!(parsed instanceof CorrectionResult.Success<?> success)) throw new IllegalArgumentException();
                            checkedNumber(success.value());
                            payload.put("mass_g",new BigDecimal(input.replace(',','.'))); origins.put("mass_g","reported");
                        }
                        data.put("mass_asked",true);
                    }
                    default -> { return prompt(u,s,"Исправления будут доступны в карточке черновика."); }
                }
                data.put("payload",payload); data.put("origins",origins);
                return prompt(u,save(owner,null,"food_clarify",data,u,"answer"),"");
            } catch(IllegalArgumentException | DateTimeException invalid) { return prompt(u,s,"Не удалось прочитать значение. Проверьте формат."); }
        }
        var image=u.image();
        if(image==null) return List.of();
        // New domain phase keys must not bypass the last accepted ordinary update.
        if (dialogs.find(owner).filter(state -> u.updateId() <= observedUpdate(state)).isPresent())
            return response(u,"Сообщение уже обработано. Продолжите текущий диалог.",List.of());
        if(image.album()) return response(u,"Отправьте одну фотографию, без альбома.",List.of());
        if(image.fileId()==null || !Set.of("image/jpeg","image/png").contains(Objects.toString(image.mediaType(),"")))
            return response(u,"Поддерживаются только JPEG и PNG.",List.of());
        if(image.size()!=null && (image.size()<1 || image.size()>ImageValidator.MAX_BYTES))
            return response(u,"Изображение должно быть не больше 5 МиБ.",List.of());
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("schema_version",1); data.put("original_update",u.updateId()); data.put("telegram_file",image.fileId());
        // Telegram's original sent time survives clarification, retries and process restarts.
        if(u.messageSentAt()!=null) data.put("message_sent_at",u.messageSentAt().toString());
        var s=save(owner,null,chooseImageClass?"food_class":"food_processing",data,u,"input");
        if(s.step().equals("food_class")) return prompt(u,s,"Выберите, что распознать на фотографии.");
        if(!s.step().equals("food_processing") || number(s.context(),"original_update")!=u.updateId())
            return response(u,"Сообщение уже обработано.",List.of());
        return recognize(u,owner,s);
    }
    public List<BotAction> callback(BotUpdate u, OwnerContext owner, ZoneId zone) {
        var cancellation=resumeCancellation(u,owner,zone);
        if(cancellation.isPresent()) return cancellation.get();
        if(u.callbackData().startsWith("pq:")) return cancelQueue(u,owner,zone);
        var found=pending(owner);
        if(found.isEmpty()) return response(u,"Действие уже завершено.",List.of());
        var s=found.get(); if (!valid(s)) return invalid(u,owner); String[] p=u.callbackData().split(":",-1);
        if(p.length!=4 || !p[2].equals(String.valueOf(number(s.context(),"original_update")))
                || !p[3].equals(String.valueOf(s.revision())) || u.updateId()<=last(s)) return prompt(u,s,"Кнопка устарела.");
        if(p[1].equals("x")) {
            Entry target=entries.findActiveDraft(owner).filter(e -> creationKey(s.context()).storageKey().equals(e.telegramUpdateKey())).orElse(null);
            if(target==null && s.context().containsKey("entry_id"))
                target=entries.requireEntry(owner,UUID.fromString((String)s.context().get("entry_id")));
            if(target!=null && !matchesPhoto(s.context(),target)) return damagedQueue(u);
            return beginCancellation(u,owner,s.context(),target,"photo",s.step(),zone);
        }
        if(s.step().equals("food_class") && Set.of("f","h","w").contains(p[1])) {
            var data=new LinkedHashMap<>(s.context());
            data.put("requested_class",switch(p[1]) { case "f" -> "food_photo"; case "h" -> "health_screenshot"; default -> "watch_photo"; });
            return recognize(u,owner,save(owner,null,"food_processing",data,u,"class"));
        }
        if(p[1].equals("r") && Set.of("food_error","food_processing").contains(s.step())) {
            var data=new LinkedHashMap<>(s.context()); data.remove("error");
            var next=save(owner,null,"food_processing",data,u,"retry");
            return recognize(u,owner,next);
        }
        if(p[1].equals("m") && Set.of("food_error","food_processing").contains(s.step())) {
            var data=new LinkedHashMap<>(s.context());
            data.put("payload",emptyPayload()); data.put("origins",Map.of());
            String requested=(String)data.get("requested_class");
            // Programmatic callers predating the multi-class UI still represent food input.
            if(requested==null && !chooseImageClass) requested="food_photo";
            data.put("manual_class",requested==null);
            if(requested!=null) {
                data.put("image_class",requested);
                if(!requested.equals("food_photo")) {
                    data.put("payload",Map.of()); data.put("candidate_index",0); data.put("candidates",List.of(Map.of()));
                }
            }
            return prompt(u,save(owner,null,"food_clarify",data,u,"manual"),"Введите сведения вручную.");
        }
        if(p[1].equals("k") && s.step().equals("food_clarify") && isMetric(s.context())) {
            var data=new LinkedHashMap<>(s.context());
            int next=((Number)data.get("candidate_index")).intValue()+1;
            if(next>=candidates(data).size()) { finish(owner,u); return response(u,"Показатель пропущен. Ввод фотографии завершён.",List.of()); }
            selectCandidate(data,next);
            return prompt(u,save(owner,null,"food_clarify",data,u,"skip"),"Показатель пропущен.");
        }
        if(p[1].equals("c") && (s.step().equals("food_commit") || (s.step().equals("food_clarify") && expected(s.context()).isEmpty())))
            return commit(u,owner,s,zone);
        return prompt(u,s,"Продолжите текущий ввод.");
    }
    private List<BotAction> cancelQueue(BotUpdate u,OwnerContext owner,ZoneId zone) {
        var found=dialogs.find(owner).filter(s -> s.context().containsKey("photo_queue"));
        if(found.isEmpty()) return response(u,"Кнопка устарела.",List.of());
        var s=found.get();
        var queued=validatedQueue(owner,s);
        if(queued.isEmpty()) return damagedQueue(u);
        String[] p=u.callbackData().split(":",-1);
        if(p.length!=4 || !p[1].equals("x") || s.activeEntryId()==null
                || !s.activeEntryId().toString().equals(p[2]) || !String.valueOf(s.revision()).equals(p[3])
                || u.updateId()<=observedUpdate(s)) return response(u,"Кнопка устарела.",List.of());
        Entry current=queued.get().entry();
        if(current.status()==EntryStatus.DRAFT) {
            if(number(s.context(),"entry_revision")!=current.revision()) {
                var context=new LinkedHashMap<String,Object>(); context.put("schema_version",1);
                context.put("entry_revision",current.revision()); context.put("timezone",zone.getId());
                context.put("photo_queue",queued.get().data());
                dialogs.save(new SaveDialogStateCommand(owner,current.id(),"draft_review",context,new TelegramUpdateKey("main",u.updateId())));
                return drafts.card(u,current,zone,"Запись изменилась. Отмена очереди не выполнена; проверьте актуальную карточку.");
            }
        }
        return beginCancellation(u,owner,queued.get().data(),current,"queue",s.step(),zone);
    }

    private List<BotAction> beginCancellation(BotUpdate u,OwnerContext owner,Map<String,Object> original,
                                             Entry target,String scope,String previousStep,ZoneId zone) {
        var data=new LinkedHashMap<>(original);
        data.put("cancel_scope",scope); data.put("cancel_source_step",previousStep);
        data.put("cancel_target",target==null?"none":target.id().toString());
        data.put("cancel_expected_revision",target==null?0:target.revision());
        data.put("cancel_update",u.updateId());
        // Intent is durable BEFORE cancelling Entry. Lost acknowledgements must not resume the tail.
        var accepted=save(owner,target==null?null:target.id(),"food_cancelling",data,u,"cancel-intent");
        if(!accepted.step().equals("food_cancelling")) return response(u,"Диалог уже изменён. Отмена не выполнена.",List.of());
        return recoverCancellation(u,owner,accepted,zone);
    }

    private Optional<List<BotAction>> resumeCancellation(BotUpdate u,OwnerContext owner,ZoneId zone) {
        return dialogs.find(owner).filter(s -> s.step().equals("food_cancelling") || s.context().containsKey("cancel_scope")
                || s.context().containsKey("cancel_target") || s.context().containsKey("cancel_update"))
                .map(s -> recoverCancellation(u,owner,s,zone));
    }

    private List<BotAction> recoverCancellation(BotUpdate u,OwnerContext owner,DialogState state,ZoneId zone) {
        var data=new LinkedHashMap<>(state.context());
        String scope;
        Entry target=null;
        long revision;
        try {
            if(!state.step().equals("food_cancelling") || !valid(state)) return damagedQueue(u);
            scope=(String)data.get("cancel_scope");
            if(!Set.of("photo","queue").contains(scope) || number(data,"cancel_update")!=last(state)
                    || number(data,"cancel_update")<number(data,"original_update")) return damagedQueue(u);
            if(u.updateId()<last(state)) return response(u,"Сообщение устарело. Отмена ещё не завершена.",List.of());
            revision=number(data,"cancel_expected_revision");
            String id=(String)data.get("cancel_target");
            String previous=(String)data.get("cancel_source_step");
            if(scope.equals("queue")) {
                if(!Set.of("draft_review","draft_pending","draft_choose","draft_await").contains(previous)
                        || !validMetricData(data,true)) return damagedQueue(u);
            } else if(!Set.of("food_class","food_processing","food_error","food_clarify","food_commit").contains(previous)) return damagedQueue(u);
            if("none".equals(id)) {
                if(state.activeEntryId()!=null || revision!=0 || scope.equals("queue") || data.containsKey("entry_id")) return damagedQueue(u);
            } else {
                UUID targetId=UUID.fromString(id);
                if(!targetId.equals(state.activeEntryId()) || revision<1) return damagedQueue(u);
                target=entries.requireEntry(owner,targetId);
                if(!matchesPhoto(data,target) || (data.containsKey("entry_id") && !id.equals(data.get("entry_id")))) return damagedQueue(u);
            }
        } catch(IllegalArgumentException | ArithmeticException | DateTimeException | EntryNotFoundException
                | NullPointerException | ClassCastException invalid) {
            return damagedQueue(u);
        }
        if(target!=null && target.status()==EntryStatus.DRAFT) {
            if(target.revision()!=revision) return cancellationConflict(u,owner,data,target,scope,zone);
            try { entries.cancel(owner,target.id(),revision); }
            catch(EntryVersionConflictException | EntryStatusConflictException conflict) {
                target=entries.requireEntry(owner,target.id());
                if(target.status()==EntryStatus.DRAFT) return cancellationConflict(u,owner,data,target,scope,zone);
                // Concurrent confirm/cancel is terminal: keep that result, discard only the remaining queue.
            }
        }
        finish(owner,u);
        return response(u,"Ввод фотографии и оставшиеся показатели отменены. Уже подтверждённые записи сохранены.",List.of());
    }

    private List<BotAction> cancellationConflict(BotUpdate u,OwnerContext owner,Map<String,Object> original,
                                                Entry current,String scope,ZoneId zone) {
        var data=new LinkedHashMap<>(original);
        for(String key:List.of("cancel_scope","cancel_source_step","cancel_target","cancel_expected_revision","cancel_update")) data.remove(key);
        String notice="Запись изменилась. Отмена не выполнена; проверьте актуальную запись и выберите действие заново.";
        if(scope.equals("queue")) {
            var context=new LinkedHashMap<String,Object>(); context.put("schema_version",1);
            context.put("entry_revision",current.revision()); context.put("timezone",zone.getId()); context.put("photo_queue",data);
            dialogs.save(new SaveDialogStateCommand(owner,current.id(),"draft_review",context,new TelegramUpdateKey("main",u.updateId())));
            return drafts.card(u,current,zone,notice);
        }
        return prompt(u,save(owner,current.id(),"food_commit",data,u,"cancel-conflict"),notice);
    }

    private static boolean matchesPhoto(Map<String,Object> data,Entry entry) {
        return creationKey(data).storageKey().equals(entry.telegramUpdateKey())
                && Objects.equals(data.get("file_id"),entry.sourceRef().get("file_id"))
                && (isMetric(data)?entry.type()==EntryType.METRICS && entry.sourceKind().name().equals(
                        ((String)data.get("image_class")).toUpperCase(Locale.ROOT)):
                        entry.type()==EntryType.MEAL && entry.sourceKind()==SourceKind.FOOD_PHOTO);
    }
    private List<BotAction> recognize(BotUpdate u,OwnerContext owner,DialogState s) {
        var data=new LinkedHashMap<>(s.context());
        try {
            var result=recognition.recognize(loader.load((String)data.get("telegram_file")),(String)data.get("requested_class"));
            var r=result.result(); data.put("image_class",r.imageClass());
            if(!r.metrics().isEmpty()) {
                data.put("candidates",r.metrics().stream().map(MetricCandidate::context).toList());
                data.put("ignored_labels",r.ignoredLabels()); data.put("operation_id",result.operationId());
                selectCandidate(data,0);
                return prompt(u,save(owner,null,"food_clarify",data,u,"recognized"),
                        "Результат модели — предложение. Показатели проверяются по одному.");
            }
            var payload=emptyPayload();
            payload.put("description",r.description()); payload.put("mass_g",r.massG());
            payload.put("nutrients_basis",r.nutrientsBasis());
            var n=new LinkedHashMap<String,Object>(); n.put("energy_kcal",r.nutrients().energyKcal());
            n.put("protein_g",r.nutrients().proteinG()); n.put("fat_g",r.nutrients().fatG()); n.put("carbs_g",r.nutrients().carbsG());
            for(var value:n.values()) checkedNumber(value);
            checkedNumber(r.massG());
            payload.put("nutrients",n); data.put("payload",payload); data.put("origins",r.fieldOrigins());
            if(r.occurredAt()!=null) data.put("occurred_at",r.occurredAt());
            if(r.localDate()!=null) data.put("date",r.localDate());
            if(r.localTime()!=null) data.put("time",r.localTime());
            data.put("operation_id",result.operationId());
            return prompt(u,save(owner,null,"food_clarify",data,u,"recognized"),"Результат модели — предложение. Проверьте его перед сохранением.");
        } catch(RecognitionException | IllegalArgumentException failure) {
            data.put("error",failure instanceof RecognitionException e?e.code().name():"INVALID_RESPONSE");
            return prompt(u,save(owner,null,"food_error",data,u,"recognition-error"),"Распознавание не выполнено. Автоматического повтора нет.");
        }
    }
    private List<BotAction> commit(BotUpdate u,OwnerContext owner,DialogState s,ZoneId zone) {
        var data=new LinkedHashMap<>(s.context());
        if(isMetric(data) && !data.containsKey("message_sent_at"))
            return prompt(u,s,"В исходном сообщении нет времени отправки. Отмените ввод и пришлите фотографию заново.");
        Instant occurred;
        try {
            if(isMetric(data)) occurred=Instant.parse((String)data.get("message_sent_at"));
            else if(data.get("occurred_at") instanceof String value) occurred=OffsetDateTime.parse(value).toInstant();
            else {
                var local=LocalDate.parse((String)data.get("date")).atTime(LocalTime.parse((String)data.get("time")));
                var offsets=zone.getRules().getValidOffsets(local);
                if(offsets.size()!=1) return prompt(u,s,"Время неоднозначно из-за перевода часов. Отмените ввод и укажите другое время.");
                occurred=local.toInstant(offsets.getFirst());
            }
        } catch(DateTimeException | NullPointerException invalid) { return prompt(u,s,"Нужны корректные дата и время."); }
        var active=entries.findActiveDraft(owner);
        String creationKey=creationKey(data).storageKey();
        if(active.isPresent() && !creationKey.equals(active.get().telegramUpdateKey()))
            return drafts.card(u,active.get(),zone,"Завершите существующий черновик; фото остаётся в диалоге.");
        try {
            s=save(owner,null,"food_commit",data,u,"commit");
            data=new LinkedHashMap<>(s.context());
            if(!data.containsKey("file_id")) {
                if (!data.containsKey("reserved_file_id")) {
                    data.put("reserved_file_id",UUID.randomUUID().toString());
                    s=save(owner,null,"food_commit",data,u,"file-reserved");
                    data=new LinkedHashMap<>(s.context());
                }
                UUID reservedId=UUID.fromString((String)data.get("reserved_file_id"));
                byte[] bytes=loader.load((String)data.get("telegram_file")); new ImageValidator().validate(bytes);
                var file=files.store(owner,reservedId,new ByteArrayInputStream(bytes),bytes.length);
                data.put("file_id",file.id().toString());
                s=save(owner,null,"food_commit",data,u,"stored"); data=new LinkedHashMap<>(s.context());
            }
            Map<String,String> origins=new LinkedHashMap<>(); origins(data).forEach((k,v)->origins.put(k,(String)v));
            if (isMetric(data)) origins.put("occurred_at","reported");
            else if (!data.containsKey("occurred_at")) {
                String dateOrigin=origins.get("local_date"), timeOrigin=origins.get("local_time");
                // Keep component evidence: mixed extracted/reported input is not wholly reported.
                String canonical=Objects.equals(dateOrigin,timeOrigin) && dateOrigin!=null
                        ? dateOrigin : "computed";
                origins.put("occurred_at",canonical);
            }
            var source=new LinkedHashMap<String,Object>(); source.put("file_id",data.get("file_id"));
            if(isMetric(data)) {
                source.put("telegram_update_id",number(data,"original_update"));
                source.put("label",sourceLabel(data));
            }
            var result=entries.createDraft(new CreateDraftCommand(owner,isMetric(data)?EntryType.METRICS:EntryType.MEAL,
                    isMetric(data)?SourceKind.valueOf(((String)data.get("image_class")).toUpperCase(Locale.ROOT)):SourceKind.FOOD_PHOTO,
                    source,occurred,numericPayload(data),origins,creationKey(data)));
            if(result.outcome()==DraftCreationResult.Outcome.ACTIVE_DRAFT_EXISTS)
                return drafts.card(u,result.entry(),zone,"Завершите существующий черновик.");
            Entry entry=result.entry(); data.put("entry_id",entry.id().toString());
            s=save(owner,entry.id(),"food_commit",data,u,"created"); data=new LinkedHashMap<>(s.context());
            files.bindToEntry(owner,UUID.fromString((String)data.get("file_id")),entry.id());
            // Handoff follows DraftReviewFlow's contract, including its main update watermark.
            var review=new LinkedHashMap<String,Object>(); review.put("schema_version",1);
            review.put("entry_revision",entry.revision()); review.put("timezone",zone.getId());
            if(isMetric(data)) review.put("photo_queue",data);
            dialogs.save(new SaveDialogStateCommand(owner,entry.id(),"draft_review",review,new TelegramUpdateKey("main",u.updateId())));
            return drafts.card(u,entry,zone,"Фото связано с черновиком. Это ещё не подтверждённая запись.");
        } catch(RecognitionException | RuntimeException failure) {
            // Never expose storage/provider exceptions or claim success after a partial failure.
            return prompt(u,pending(owner).orElse(s),"Подготовка черновика не завершена. Нажмите «Продолжить», чтобы восстановить связь.");
        }
    }
    private DialogState save(OwnerContext owner,UUID entry,String step,Map<String,Object> original,BotUpdate u,String phase) {
        var data=new LinkedHashMap<>(original); data.put("last_update",u.updateId());
        if (data.containsKey("origins")) {
            var encoded=new LinkedHashMap<String,Object>();
            origins(data).forEach((k,v)->encoded.put(k.replace(".","|"),v)); data.put("origins",encoded);
        }
        return dialogs.save(new SaveDialogStateCommand(owner,entry,step,DateTimePicker.rotate(data),new TelegramUpdateKey("main-food-"+phase,u.updateId())));
    }
    public Optional<DateTimePicker.Form> pickerForm(DialogState state) {
        if (!valid(state) || !state.step().equals("food_clarify")
                || !Set.of("date", "time", "metric_date").contains(expected(state.context()))) return Optional.empty();
        var data = state.context();
        if (isMetric(data)) return Optional.of(new DateTimePicker.Form(null, null, false, false, false,
                switch (Objects.toString(map(data.get("payload")).get("code"), "")) {
                    case "steps" -> "День итога шагов"; case "sleep_duration_min" -> "Дата пробуждения";
                    default -> "Дата измерения пульса";
                }));
        String date = data.get("date") instanceof String d ? d : null;
        String time = data.get("time") instanceof String t ? t : null;
        return Optional.of(new DateTimePicker.Form(date, time, true, date != null, time != null, "Дата и время приёма пищи"));
    }
    public List<BotAction> applyPicker(BotUpdate update, OwnerContext owner, ZoneId zone, DialogState state,
                                      DateTimePicker.Selection selection) {
        if (update.updateId() <= last(state)) return prompt(update, state, "Выбор уже обработан.");
        var data = new LinkedHashMap<>(state.context());
        var payload = map(data.get("payload")); var origin = origins(data);
        if (isMetric(data)) {
            payload.put("local_date", selection.date().toString()); origin.put("local_date", "reported");
        } else {
            if (!data.containsKey("date")) origin.put("local_date", "reported");
            if (!data.containsKey("time")) origin.put("local_time", "reported");
            data.put("date", selection.date().toString()); data.put("time", selection.time().toString());
        }
        data.put("payload", payload); data.put("origins", origin);
        DateTimePicker.recordReceipt(data, update.webAppData(), update.updateId());
        return prompt(update, save(owner, null, "food_clarify", data, update, "answer"),
                "Выбрано: " + selection.date() + (selection.time() == null ? "" : " " + selection.time()) + ".");
    }
    private List<BotAction> prompt(BotUpdate u,DialogState s,String notice) {
        var rows=new ArrayList<List<BotAction.InlineButton>>(); String question="";
        if(s.step().equals("food_class")) rows.add(List.of(button("Еда","f",s),button("Экран здоровья","h",s),button("Часы","w",s)));
        if(Set.of("food_error","food_processing").contains(s.step())) {
            rows.add(List.of(button("Повторить","r",s),button("Ввести вручную","m",s)));
            question="Если текст не читается или на изображении несколько типов объектов, отмените ввод и пришлите чёткий фрагмент нужного экрана.";
        }
        if(s.step().equals("food_clarify")) {
            question=switch(expected(s.context())) {
                case "image_class" -> "Что вы вводите: еда, здоровье или часы?";
                case "code" -> "Какой показатель ввести вручную: шаги, сон или пульс?";
                case "value" -> "Введите числовое значение показателя"+(map(s.context().get("payload")).get("unit")==null?".":" в "+map(s.context().get("payload")).get("unit")+".");
                case "unit" -> "Укажите единицу исходного числа: count/шаги, min/мин/ч или bpm/уд/мин.";
                case "metric_date" -> "Введите дату ДД.ММ.ГГГГ: для шагов — день итога, для сна — дата пробуждения, для пульса — дата измерения.";
                case "description" -> "Что на фото? Введите описание еды.";
                case "date" -> "Когда был приём пищи? Введите дату ДД.ММ.ГГГГ.";
                case "time" -> "Введите время ЧЧ:ММ (часовой пояс профиля).";
                case "mass" -> "Укажите массу порции в граммах или «не знаю».";
                default -> isMetric(s.context())?"Неизвестные время и контекст останутся неизвестными. Проверьте показатель в карточке.":"Неизвестные калории и БЖУ останутся неизвестными. Их можно изменить в карточке.";
            };
            if(expected(s.context()).isEmpty()) rows.add(List.of(button("Создать черновик","c",s)));
            if(isMetric(s.context())) rows.add(List.of(button("Пропустить показатель","k",s)));
        }
        if(s.step().equals("food_commit")) rows.add(List.of(button("Продолжить","c",s)));
        rows.add(List.of(button("Отменить","x",s)));
        if(isMetric(s.context())) {
            var payload=map(s.context().get("payload"));
            question="Показатель "+(((Number)s.context().getOrDefault("candidate_index",0)).intValue()+1)+" из "+candidates(s.context()).size()
                    +": "+switch(Objects.toString(payload.get("code"),"")) {
                        case "steps" -> "Шаги"; case "sleep_duration_min" -> "Сон"; case "heart_rate" -> "Пульс"; default -> "выберите показатель";
                    }+"\n"+question;
            if(s.context().get("ignored_labels") instanceof List<?> ignored && !ignored.isEmpty())
                question += "\nНе импортируются (поддерживаются только шаги, сон и пульс): "+String.join(", ",ignored.stream().map(Object::toString).toList());
        }
        return response(u,"Режим: "+mode+(mode.equals("FIXTURE")?" — учебный ответ, фото не распознаётся.":".")+"\n"+notice+"\n"+question,rows);
    }
    private static String expected(Map<String,Object> data) {
        var p=map(data.get("payload"));
        if(Boolean.TRUE.equals(data.get("manual_class"))) return "image_class";
        if(isMetric(data)) {
            if(p.get("code")==null) return "code";
            if(p.get("value")==null) return "value";
            if(p.get("unit")==null) return "unit";
            if(p.get("local_date")==null) return "metric_date";
            return "";
        }
        if(!(p.get("description") instanceof String text) || text.isBlank()) return "description";
        if(!data.containsKey("occurred_at")) {
            if(!data.containsKey("date")) return "date";
            if(!data.containsKey("time")) return "time";
        }
        if(p.get("mass_g")==null && !Boolean.TRUE.equals(data.get("mass_asked"))) return "mass";
        return "";
    }
    private static Map<String,Object> emptyPayload() {
        var p=new LinkedHashMap<String,Object>();p.put("description",null);p.put("mass_g",null);
        var n=new LinkedHashMap<String,Object>();for(String k:List.of("energy_kcal","protein_g","fat_g","carbs_g"))n.put(k,null);
        p.put("nutrients",n);p.put("nutrients_basis","unknown");return p;
    }
    private static Map<String,Object> origins(Map<String,Object> data) {
        var decoded=new LinkedHashMap<String,Object>();
        map(data.get("origins")).forEach((k,v)->decoded.put(k.replace("|","."),v)); return decoded;
    }
    private static Map<String,Object> numericPayload(Map<String,Object> data) {
        var payload=map(data.get("payload"));
        if(isMetric(data)) {
            if(payload.get("value")!=null) payload.put("value",new BigDecimal(payload.get("value").toString()));
            return payload;
        }
        if(payload.get("mass_g")!=null)payload.put("mass_g",new BigDecimal(payload.get("mass_g").toString()));
        var nutrients=map(payload.get("nutrients"));
        nutrients.replaceAll((k,v)->v==null?null:new BigDecimal(v.toString())); payload.put("nutrients",nutrients);return payload;
    }
    private static void checkedNumber(Object value) {
        if(value!=null) { var n=new BigDecimal(value.toString()); if(n.signum()<0 || n.precision()>2000 || Math.abs((long)n.scale())>2000 || !Double.isFinite(n.doubleValue()))throw new IllegalArgumentException(); }
    }
    private List<BotAction> invalid(BotUpdate u,OwnerContext owner) {
        if(dialogs.find(owner).filter(s -> s.context().containsKey("candidates") || s.context().containsKey("photo_queue")
                || isMetric(s.context())).isPresent()) return damagedQueue(u);
        finish(owner,u);
        return response(u,"Не удалось восстановить ввод фотографии. Существующий черновик не изменён. Отправьте сообщение заново.",List.of());
    }
    private void finish(OwnerContext owner,BotUpdate u) {
        // Publish the terminal photo update to the ordinary flow watermark as well.
        dialogs.save(new SaveDialogStateCommand(owner,null,"idle",Map.of("schema_version",1,"last_photo_update",u.updateId()),
                new TelegramUpdateKey("main-food-finish",u.updateId())));
    }
    public static long observedUpdate(DialogState state) {
        String key=state.telegramUpdateKey();
        if(key==null || !key.matches("main(?:-food-[a-z-]+)?:[0-9]+")) return -1;
        try { return Long.parseLong(key.substring(key.lastIndexOf(':')+1)); }
        catch(NumberFormatException invalid) { return -1; }
    }
    private static boolean valid(DialogState s) {
        try {
            var d=s.context();
            if (!Set.of("food_class","food_processing","food_error","food_clarify","food_commit","food_cancelling").contains(s.step())
                || number(d,"schema_version")!=1 || number(d,"original_update")<0 || number(d,"original_update")>last(s)
                || !(d.get("telegram_file") instanceof String f) || f.isBlank()) return false;
            if (s.step().equals("food_clarify") || s.step().equals("food_commit")) {
                if (!(d.get("payload") instanceof Map<?,?>) || !(d.get("origins") instanceof Map<?,?>)) return false;
                numericPayload(d); origins(d).values().forEach(v -> { if (!(v instanceof String)) throw new IllegalArgumentException(); });
            }
            if(d.containsKey("reserved_file_id")) UUID.fromString((String)d.get("reserved_file_id"));
            if(d.containsKey("file_id")) UUID.fromString((String)d.get("file_id"));
            if(d.containsKey("entry_id")) UUID.fromString((String)d.get("entry_id"));
            if(d.containsKey("date")) LocalDate.parse((String)d.get("date"));
            if(d.containsKey("time")) LocalTime.parse((String)d.get("time"));
            if(d.containsKey("occurred_at")) OffsetDateTime.parse((String)d.get("occurred_at"));
            if(d.containsKey("message_sent_at")) Instant.parse((String)d.get("message_sent_at"));
            if(isMetric(d) && !validMetricData(d,false)) return false;
            return true;
        } catch(RuntimeException failure) { return false; }
    }
    private static boolean isMetric(Map<String,Object> data) {
        return Set.of("health_screenshot","watch_photo").contains(Objects.toString(data.get("image_class"),""));
    }
    private record QueuedEntry(Map<String,Object> data,Entry entry) { }

    /** Persisted data is untrusted: validate the complete tail before advancing OR cancelling it. */
    private Optional<QueuedEntry> validatedQueue(OwnerContext owner,DialogState state) {
        try {
            if(!(state.context().get("photo_queue") instanceof Map<?,?>) || state.activeEntryId()==null)
                return Optional.empty();
            if(number(state.context(),"schema_version")!=1 || number(state.context(),"entry_revision")<1)
                return Optional.empty();
            var data=map(state.context().get("photo_queue"));
            if(!validMetricData(data,true) || !state.activeEntryId().toString().equals(data.get("entry_id")))
                return Optional.empty();
            Entry entry=entries.requireEntry(owner,state.activeEntryId());
            if(entry.type()!=EntryType.METRICS || !entry.ownerId().equals(owner.userId())
                    || !entry.sourceKind().name().equals(((String)data.get("image_class")).toUpperCase(Locale.ROOT))
                    || !creationKey(data).storageKey().equals(entry.telegramUpdateKey())
                    || !Objects.equals(entry.sourceRef().get("file_id"),data.get("file_id"))
                    || !entry.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).equals(
                            Instant.parse((String)data.get("message_sent_at")).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))
                return Optional.empty();
            return Optional.of(new QueuedEntry(data,entry));
        } catch(IllegalArgumentException | ArithmeticException | DateTimeException | EntryNotFoundException invalid) {
            return Optional.empty();
        }
    }

    private static boolean validMetricData(Map<String,Object> data,boolean handedOff) {
        try {
            if(!isMetric(data) || number(data,"schema_version")!=1 || number(data,"original_update")<0
                    || number(data,"last_update")<number(data,"original_update")
                    || !(data.get("telegram_file") instanceof String file) || file.isBlank()
                    || !(data.get("payload") instanceof Map<?,?>) || !(data.get("origins") instanceof Map<?,?>)) return false;
            if(data.containsKey("requested_class") && !Objects.equals(data.get("requested_class"),data.get("image_class"))) return false;
            var all=candidates(data);
            int index=Math.toIntExact(number(data,"candidate_index"));
            if(index<0 || index>=all.size()) return false;
            var current=map(data.get("payload"));
            if(!validMetricPayload(current,origins(data),!handedOff)) return false;
            var seen=new HashSet<String>();
            for(int i=0;i<all.size();i++) {
                var candidate=all.get(i);
                // The sole manual candidate has no model proposal; its answers live in the current payload.
                if(candidate.isEmpty() && all.size()==1 && index==0) continue;
                if(!Set.of("payload","origins","raw_metric").containsAll(candidate.keySet())
                        || !(candidate.get("payload") instanceof Map<?,?>) || !(candidate.get("origins") instanceof Map<?,?>)) return false;
                var payload=map(candidate.get("payload"));
                if(!validMetricPayload(payload,origins(candidate),true)
                        || !(payload.get("code") instanceof String code) || !seen.add(code)) return false;
                if(i==index && !Objects.equals(payload.get("code"),current.get("code"))) return false;
                if(candidate.containsKey("raw_metric") && !validRawMetric(candidate.get("raw_metric"))) return false;
            }
            if(data.containsKey("raw_metric") && !validRawMetric(data.get("raw_metric"))) return false;
            if(data.containsKey("message_sent_at")) Instant.parse((String)data.get("message_sent_at"));
            if(data.containsKey("ignored_labels")) {
                if(!(data.get("ignored_labels") instanceof List<?> labels) || labels.size()>20) return false;
                for(Object label:labels) if(!(label instanceof String text) || text.isBlank() || text.codePointCount(0,text.length())>100) return false;
            }
            for(String key:List.of("entry_id","file_id","reserved_file_id"))
                if(data.containsKey(key)) UUID.fromString((String)data.get(key));
            if(handedOff && (!data.containsKey("entry_id") || !data.containsKey("file_id")
                    || !Objects.equals(data.get("file_id"),data.get("reserved_file_id"))
                    || !data.containsKey("message_sent_at"))) return false;
            return true;
        } catch(RuntimeException invalid) { return false; }
    }

    private static boolean validMetricPayload(Map<String,Object> payload,Map<String,Object> origins,boolean incomplete) {
        var fields=Set.of("code","value","unit","local_date","local_time","qualifier");
        if(!fields.containsAll(payload.keySet()) || !fields.containsAll(origins.keySet())) return false;
        if(payload.isEmpty()) return incomplete && origins.isEmpty();
        if(!(payload.get("code") instanceof String code) || !Set.of("steps","sleep_duration_min","heart_rate").contains(code)) return false;
        for(String field:fields) {
            Object value=payload.get(field),origin=origins.get(field);
            if(value==null ? origin!=null : !Set.of("reported","extracted","computed").contains(Objects.toString(origin,""))) return false;
        }
        if(payload.get("value")!=null) checkedNumber(payload.get("value"));
        if(payload.get("unit")!=null && !Objects.equals(payload.get("unit"),switch(code) {
            case "steps" -> "count"; case "sleep_duration_min" -> "min"; default -> "bpm";
        })) return false;
        if(payload.get("local_date")!=null) LocalDate.parse((String)payload.get("local_date"));
        if(payload.get("local_time")!=null) {
            if(code.equals("steps") || !(payload.get("local_time") instanceof String time)
                    || !time.matches("([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?")) return false;
            LocalTime.parse(time);
        }
        if(payload.get("qualifier")!=null && (!code.equals("heart_rate")
                || !Set.of("instant","resting").contains(payload.get("qualifier")))) return false;
        return incomplete || (payload.get("value")!=null && payload.get("unit")!=null && payload.get("local_date")!=null);
    }

    private static boolean validRawMetric(Object raw) {
        if(!(raw instanceof Map<?,?>)) return false;
        var evidence=map(raw);
        if(!Set.of("value","unit","minutes_component").containsAll(evidence.keySet())) return false;
        if(evidence.get("value")!=null) checkedNumber(evidence.get("value"));
        if(evidence.get("unit")!=null && (!(evidence.get("unit") instanceof String unit)
                || unit.isBlank() || unit.codePointCount(0,unit.length())>80)) return false;
        if(evidence.get("minutes_component")!=null) {
            var minutes=new BigDecimal(evidence.get("minutes_component").toString());
            if(minutes.signum()<0 || minutes.compareTo(BigDecimal.valueOf(60))>=0 || minutes.stripTrailingZeros().scale()>0) return false;
        }
        return true;
    }

    private static List<BotAction> damagedQueue(BotUpdate u) {
        return response(u,"Очередь фотографий повреждена: не удалось восстановить её безопасно. Существующие записи и оставшиеся данные не изменены. Требуется восстановление очереди.",List.of());
    }
    private static String sourceLabel(Map<String,Object> data) {
        var raw=map(data.get("raw_metric"));
        String value=raw.get("value")==null?"неизвестно":new BigDecimal(raw.get("value").toString()).stripTrailingZeros().toString();
        String label="На изображении: "+value+" "+Objects.toString(raw.get("unit"),"единица неизвестна");
        if(raw.get("minutes_component")!=null) label+=" + "+raw.get("minutes_component")+" мин";
        if(data.get("reported_unit")!=null) label+="; единица уточнена: "+data.get("reported_unit");
        // The complete source is always the owned image. Never put an unbounded value into the API label.
        return label.codePointCount(0,label.length())<=256?label:"Исходные значения и единицы — на изображении; уточнения отражены в происхождении полей.";
    }
    private static TelegramUpdateKey creationKey(Map<String,Object> data) {
        return new TelegramUpdateKey(isMetric(data)?"main-metric-entry-"+data.get("candidate_index"):"main-food-entry",number(data,"original_update"));
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> candidates(Map<String,Object> data) {
        if(!(data.get("candidates") instanceof List<?> list) || list.isEmpty() || list.size()>3
                || list.stream().anyMatch(candidate -> !(candidate instanceof Map<?,?>)))
            throw new IllegalArgumentException("Invalid candidate list");
        return (List<Map<String,Object>>)(List<?>)list;
    }
    private static void selectCandidate(Map<String,Object> data,int index) {
        for(String key:List.of("file_id","reserved_file_id","entry_id","payload","origins","raw_metric","reported_unit")) data.remove(key);
        data.putAll(candidates(data).get(index)); data.put("candidate_index",index);
    }
    private static long last(DialogState s){return number(s.context(),"last_update");}
    private static long number(Map<String,Object> data,String key){
        if(!(data.get(key) instanceof Number value)) throw new IllegalArgumentException("Missing integer field");
        return new BigDecimal(value.toString()).longValueExact();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){return v instanceof Map<?,?> ? new LinkedHashMap<>((Map<String,Object>)v):new LinkedHashMap<>();}
    private static BotAction.InlineButton button(String title,String action,DialogState s){return new BotAction.InlineButton(title,"fp:"+action+":"+number(s.context(),"original_update")+":"+s.revision());}
    private static List<BotAction> response(BotUpdate u,String text,List<List<BotAction.InlineButton>> rows){
        var result=new ArrayList<BotAction>();if(u.callbackId()!=null)result.add(new BotAction.AnswerCallback(u.callbackId(),null));
        result.add(new BotAction.SendInlineMessage(u.chatId(),text,rows));return List.copyOf(result);
    }
}
