# Общие UI-компоненты FE1-02

Компоненты не знают про API-клиент и получают действия callback-ами.

```tsx
<Card title="Дневник" subtitle="Записи здоровья">
  <StateView variant="empty" title="Записей нет" message="Попробуйте изменить фильтры." />
</Card>

<StateView
  variant="error"
  title="Не удалось загрузить"
  message="Ошибка API не показывается как пустой список."
  actionLabel="Повторить"
  onAction={reload}
/>

<LoadingState title="Загрузка записей" message="Получаем дневник из API." />
<EmptyState title="Записей нет" message="Попробуйте изменить фильтры." />

<Button variant="danger" isLoading={isDeleting} onClick={remove}>
  Удалить
</Button>
```
