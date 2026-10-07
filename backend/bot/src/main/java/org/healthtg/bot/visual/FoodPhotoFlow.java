package org.healthtg.bot.visual;

import org.healthtg.bot.*;
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
    public FoodPhotoFlow(EntryCoreService entries, DialogStateService dialogs, FileStorageService files,
                         FoodRecognitionService recognition, ImageLoader loader, DraftReviewFlow drafts, String mode) {
        this.entries=entries; this.dialogs=dialogs; this.files=files; this.recognition=recognition;
        this.loader=loader; this.drafts=drafts; this.mode=mode;
    }
    public Optional<List<BotAction>> guard(BotUpdate u, OwnerContext owner) {
        return pending(owner).map(s -> valid(s) ? prompt(u,s,"Сначала завершите или отмените ввод фотографии.") : invalid(u,owner));
    }
    public boolean hasPending(OwnerContext owner) { return pending(owner).isPresent(); }
    private Optional<DialogState> pending(OwnerContext owner) {
        return dialogs.find(owner).filter(s -> s.step().startsWith("food_"));
    }
    public List<BotAction> message(BotUpdate u, OwnerContext owner, ZoneId zone) {
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
        var s=save(owner,null,"food_processing",data,u,"input");
        if(!s.step().equals("food_processing") || number(s.context(),"original_update")!=u.updateId())
            return response(u,"Сообщение уже обработано.",List.of());
        return recognize(u,owner,s);
    }
    public List<BotAction> callback(BotUpdate u, OwnerContext owner, ZoneId zone) {
        var found=pending(owner);
        if(found.isEmpty()) return response(u,"Действие уже завершено.",List.of());
        var s=found.get(); if (!valid(s)) return invalid(u,owner); String[] p=u.callbackData().split(":",-1);
        if(p.length!=4 || !p[2].equals(String.valueOf(number(s.context(),"original_update")))
                || !p[3].equals(String.valueOf(s.revision())) || u.updateId()<=last(s)) return prompt(u,s,"Кнопка устарела.");
        if(p[1].equals("x")) {
            var created=entries.findActiveDraft(owner).filter(e -> new TelegramUpdateKey("main-food-entry",number(s.context(),"original_update")).storageKey().equals(e.telegramUpdateKey()));
            if(created.isPresent()) entries.cancel(owner,created.get().id(),created.get().revision());
            else if(s.context().containsKey("entry_id")) {
                Entry e=entries.requireEntry(owner,UUID.fromString((String)s.context().get("entry_id")));
                if(e.status()==EntryStatus.DRAFT) entries.cancel(owner,e.id(),e.revision());
            }
            finish(owner,u);
            return response(u,"Ввод фотографии отменён.",List.of());
        }
        if(p[1].equals("r") && Set.of("food_error","food_processing").contains(s.step())) {
            var data=new LinkedHashMap<>(s.context()); data.remove("error");
            var next=save(owner,null,"food_processing",data,u,"retry");
            return recognize(u,owner,next);
        }
        if(p[1].equals("m") && Set.of("food_error","food_processing").contains(s.step())) {
            var data=new LinkedHashMap<>(s.context());
            data.put("payload",emptyPayload()); data.put("origins",Map.of());
            return prompt(u,save(owner,null,"food_clarify",data,u,"manual"),"Введите сведения вручную.");
        }
        if(p[1].equals("c") && (s.step().equals("food_commit") || (s.step().equals("food_clarify") && expected(s.context()).isEmpty())))
            return commit(u,owner,s,zone);
        return prompt(u,s,"Продолжите текущий ввод.");
    }
    private List<BotAction> recognize(BotUpdate u,OwnerContext owner,DialogState s) {
        var data=new LinkedHashMap<>(s.context());
        try {
            var result=recognition.recognize(loader.load((String)data.get("telegram_file")));
            var r=result.result(); var payload=emptyPayload();
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
        Instant occurred;
        try {
            if(data.get("occurred_at") instanceof String value) occurred=OffsetDateTime.parse(value).toInstant();
            else {
                var local=LocalDate.parse((String)data.get("date")).atTime(LocalTime.parse((String)data.get("time")));
                var offsets=zone.getRules().getValidOffsets(local);
                if(offsets.size()!=1) return prompt(u,s,"Время неоднозначно из-за перевода часов. Отмените ввод и укажите другое время.");
                occurred=local.toInstant(offsets.getFirst());
            }
        } catch(DateTimeException | NullPointerException invalid) { return prompt(u,s,"Нужны корректные дата и время."); }
        var active=entries.findActiveDraft(owner);
        String creationKey=new TelegramUpdateKey("main-food-entry",number(data,"original_update")).storageKey();
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
            if (!data.containsKey("occurred_at")) {
                String dateOrigin=origins.get("local_date"), timeOrigin=origins.get("local_time");
                // Keep component evidence: mixed extracted/reported input is not wholly reported.
                String canonical=Objects.equals(dateOrigin,timeOrigin) && dateOrigin!=null
                        ? dateOrigin : "computed";
                origins.put("occurred_at",canonical);
            }
            var result=entries.createDraft(new CreateDraftCommand(owner,EntryType.MEAL,SourceKind.FOOD_PHOTO,
                    Map.of("file_id",data.get("file_id")),occurred,numericPayload(data),origins,
                    new TelegramUpdateKey("main-food-entry",number(data,"original_update"))));
            if(result.outcome()==DraftCreationResult.Outcome.ACTIVE_DRAFT_EXISTS)
                return drafts.card(u,result.entry(),zone,"Завершите существующий черновик.");
            Entry entry=result.entry(); data.put("entry_id",entry.id().toString());
            s=save(owner,entry.id(),"food_commit",data,u,"created"); data=new LinkedHashMap<>(s.context());
            files.bindToEntry(owner,UUID.fromString((String)data.get("file_id")),entry.id());
            // Handoff follows DraftReviewFlow's contract, including its main update watermark.
            dialogs.save(new SaveDialogStateCommand(owner,entry.id(),"draft_review",
                    Map.of("schema_version",1,"entry_revision",entry.revision(),"timezone",zone.getId()),
                    new TelegramUpdateKey("main",u.updateId())));
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
        return dialogs.save(new SaveDialogStateCommand(owner,entry,step,data,new TelegramUpdateKey("main-food-"+phase,u.updateId())));
    }
    private List<BotAction> prompt(BotUpdate u,DialogState s,String notice) {
        var rows=new ArrayList<List<BotAction.InlineButton>>(); String question="";
        if(Set.of("food_error","food_processing").contains(s.step())) rows.add(List.of(button("Повторить","r",s),button("Ввести вручную","m",s)));
        if(s.step().equals("food_clarify")) {
            question=switch(expected(s.context())) {
                case "description" -> "Что на фото? Введите описание еды.";
                case "date" -> "Когда был приём пищи? Введите дату ДД.ММ.ГГГГ.";
                case "time" -> "Введите время ЧЧ:ММ (часовой пояс профиля).";
                case "mass" -> "Укажите массу порции в граммах или «не знаю».";
                default -> "Неизвестные калории и БЖУ останутся неизвестными. Их можно изменить в карточке.";
            };
            if(expected(s.context()).isEmpty()) rows.add(List.of(button("Создать черновик","c",s)));
        }
        if(s.step().equals("food_commit")) rows.add(List.of(button("Продолжить","c",s)));
        rows.add(List.of(button("Отменить","x",s)));
        return response(u,"Режим: "+mode+(mode.equals("FIXTURE")?" — учебный ответ, фото не распознаётся.":".")+"\n"+notice+"\n"+question,rows);
    }
    private static String expected(Map<String,Object> data) {
        var p=map(data.get("payload"));
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
        if(payload.get("mass_g")!=null)payload.put("mass_g",new BigDecimal(payload.get("mass_g").toString()));
        var nutrients=map(payload.get("nutrients"));
        nutrients.replaceAll((k,v)->v==null?null:new BigDecimal(v.toString())); payload.put("nutrients",nutrients);return payload;
    }
    private static void checkedNumber(Object value) {
        if(value!=null) { var n=new BigDecimal(value.toString()); if(n.signum()<0 || n.precision()>2000 || Math.abs((long)n.scale())>2000 || !Double.isFinite(n.doubleValue()))throw new IllegalArgumentException(); }
    }
    private List<BotAction> invalid(BotUpdate u,OwnerContext owner) {
        finish(owner,u);
        return response(u,"Не удалось восстановить ввод фотографии. Существующий черновик не изменён. Отправьте сообщение заново.",List.of());
    }
    private void finish(OwnerContext owner,BotUpdate u) {
        // Publish the terminal photo update to the ordinary flow watermark as well.
        dialogs.save(new SaveDialogStateCommand(owner,null,"idle",Map.of("schema_version",1,"last_photo_update",u.updateId()),
                new TelegramUpdateKey("main",u.updateId())));
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
            if (!Set.of("food_processing","food_error","food_clarify","food_commit").contains(s.step())
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
            return true;
        } catch(RuntimeException failure) { return false; }
    }
    private static long last(DialogState s){return number(s.context(),"last_update");}
    private static long number(Map<String,Object> data,String key){return ((Number)data.get(key)).longValue();}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){return v instanceof Map<?,?> ? new LinkedHashMap<>((Map<String,Object>)v):new LinkedHashMap<>();}
    private static BotAction.InlineButton button(String title,String action,DialogState s){return new BotAction.InlineButton(title,"fp:"+action+":"+number(s.context(),"original_update")+":"+s.revision());}
    private static List<BotAction> response(BotUpdate u,String text,List<List<BotAction.InlineButton>> rows){
        var result=new ArrayList<BotAction>();if(u.callbackId()!=null)result.add(new BotAction.AnswerCallback(u.callbackId(),null));
        result.add(new BotAction.SendInlineMessage(u.chatId(),text,rows));return List.copyOf(result);
    }
}
