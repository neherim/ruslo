# Spring Boot

[English](../spring.md) | Русский

`ruslo-spring-boot-starter` строит движок из `DataSource` и transaction manager приложения. Настройки `ruslo.*` ему
не нужны: при запуске он создаёт таблицы движка, транзакционный worker `DEFAULT` и запускает диспетчер. Настройки на
случай, когда значения по умолчанию не подходят, — в [configuration.md](configuration.md).

```xml
<dependency>
    <groupId>io.github.neherim</groupId>
    <artifactId>ruslo-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Starter регистрирует бины `ProcessEngine` и `ProcessEngineAdmin`. Внедряйте их там, где запускаются процессы,
отправляются сообщения или процессами управляют вручную.

## Definitions

Каждый бин `ProcessDefinition` регистрируется в движке при запуске. Наследуйте `SpringProcessDefinition`, чтобы брать
шаги и обработчики из контекста через `bean(Type.class)`:

```java
@Component
public class OrderProcessDefinition extends SpringProcessDefinition {
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder.type(TYPE)
            .initialState(CREATED)
            .step(CREATED, bean(CreateOrderStep.class), WAITING_FOR_PAYMENT)
            // ...
            .build();
    }
}
```

`define` вызывается после создания всех singleton-бинов, поэтому `bean(...)` не вызывает ранней инициализации и
циклических зависимостей. Внедрение через конструктор в обычный бин `ProcessDefinition` тоже работает.

## Свойства

Свойства начинаются с `ruslo.*`; полный список — в [configuration.md](configuration.md#настройки).
Неизвестные свойства `ruslo.*` ломают запуск, поэтому опечатка не игнорируется молча.

Кроме `ruslo.workers.*`, worker можно объявить бином `WorkerSpec`; worker из свойств заменяет бин с тем же id.

## Транзакции приложения

Операции движка присоединяются к активной транзакции приложения (`PROPAGATION_REQUIRED`). Поэтому
`newProcess(...).start()` и `sendMessage()` внутри `@Transactional` атомарны с изменениями приложения на том же
`DataSource` и transaction manager: при откате не сохраняется ни то, ни другое. Шаги процесса выполняются после
commit, каждый в своей транзакции.

Ошибка движка (ошибка базы данных, `ProcessNotFoundException`, необъявленный тип сообщения) бросается внутри
транзакции и, как обычно в Spring, помечает её rollback-only, даже если её перехватили.

Ожидаемый отказ бросается после выхода из транзакции движка: `MessageNotExpectedException` из `sendMessage` и
`sendMessageSync`, `ProcessStatusException` из `resumeFailedProcess`. Перехваченный отказ не мешает commit транзакции
приложения; неперехваченный откатывает её, как любое исключение.

## Тесты

`ruslo-spring-boot-starter-test` добавляет `@ProcessEngineTest` и эмулятор: [testing.md](testing.md).
