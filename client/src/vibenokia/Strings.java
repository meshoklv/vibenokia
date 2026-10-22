package vibenokia;

/**
 * UI strings for Russian and English.
 */
public class Strings {
    public static final int RU = 0;
    public static final int EN = 1;

    private static int lang = RU;

    public static void setLang(int l) { lang = l; }
    public static int getLang() { return lang; }
    public static String langCode() { return lang == EN ? "en" : "ru"; }

    // Commands
    public static String send() { return lang == EN ? "Send" : "Отпр."; }
    public static String voice() { return lang == EN ? "Voice" : "Голос"; }
    public static String exit() { return lang == EN ? "Exit" : "Выход"; }
    public static String back() { return lang == EN ? "Back" : "Назад"; }
    public static String settings() { return lang == EN ? "Settings" : "Настр."; }
    public static String clear() { return lang == EN ? "Clear" : "Очистить"; }
    public static String newChat() { return lang == EN ? "New Chat" : "Новый чат"; }
    public static String save() { return lang == EN ? "Save" : "Сохр."; }
    public static String sessions() { return lang == EN ? "Sessions" : "Сессии"; }
    public static String delete() { return lang == EN ? "Delete" : "Удалить"; }
    public static String switchTo() { return lang == EN ? "Open" : "Открыть"; }
    public static String change() { return lang == EN ? "Change" : "Сменить"; }
    public static String stop() { return lang == EN ? "Stop" : "Стоп"; }
    public static String cancel() { return lang == EN ? "Cancel" : "Отмена"; }
    public static String update() { return lang == EN ? "Update app" : "Обновить прогр."; }

    // Status
    public static String thinking() { return lang == EN ? "thinking..." : "думаю..."; }
    public static String recording() { return lang == EN ? "recording..." : "запись..."; }
    public static String sending() { return lang == EN ? "sending..." : "отправка..."; }
    public static String transcribing() { return lang == EN ? "transcribing..." : "распознаю..."; }
    public static String received() { return lang == EN ? "received" : "получено"; }
    public static String cleared() { return lang == EN ? "--- cleared ---" : "--- очищено ---"; }
    public static String newSessionStarted() { return lang == EN ? "--- new session ---" : "--- новая сессия ---"; }
    public static String voiceCancelled() { return lang == EN ? "cancelled" : "ГС отменено"; }
    public static String stopped() { return lang == EN ? "--- stopped ---" : "--- остановлено ---"; }
    public static String noSessions() { return lang == EN ? "No sessions yet" : "Нет сессий"; }
    public static String emptyResponse() { return lang == EN ? "[empty response]" : "[пустой ответ]"; }

    // Welcome
    public static String welcome1() { return lang == EN ? "Send - type message" : "Отпр. - набрать текст"; }
    public static String welcome2() { return lang == EN ? "hold * - voice message" : "зажми * - голос. сообщ."; }
    public static String welcome3() { return lang == EN ? "# - stop response" : "# - остановить ответ"; }
    public static String welcome4() { return lang == EN ? "9 or Menu>Help - keys" : "9 или Menu>Помощь - клавиши"; }
    public static String about() { return lang == EN ? "About" : "О программе"; }
    public static String refreshChat() { return lang == EN ? "Refresh chat" : "Обновить чат"; }
    public static String help() { return lang == EN ? "Help" : "Помощь"; }
    public static String helpTitle() { return lang == EN ? "--- keys ---" : "--- клавиши ---"; }
    public static String help1() { return lang == EN ? "Send - type message" : "Отпр. - набрать текст"; }
    public static String help2() { return lang == EN ? "hold * - voice" : "зажми * - голосовое"; }
    public static String help3() { return lang == EN ? "2/8 line, 0/# page" : "2/8 строка, 0/# страница"; }
    public static String help4() { return lang == EN ? "4/6 - top/end of chat" : "4/6 - начало/конец чата"; }
    public static String help5() { return lang == EN ? "# - stop during reply" : "# - стоп во время ответа"; }
    public static String help6() { return lang == EN ? "1 home  3 model" : "1 домой  3 модель"; }
    public static String help7() { return lang == EN ? "5 refresh  7 clear" : "5 обновить  7 очистить"; }
    public static String help8() { return lang == EN ? "Menu - everything else" : "Menu - всё остальное"; }
    public static String lastText() { return lang == EN ? "Last" : "Последнее"; }
    public static String fontSize() { return lang == EN ? "Font:" : "Шрифт:"; }
    public static String fontSmall() { return lang == EN ? "Small" : "Мелкий"; }
    public static String fontMedium() { return lang == EN ? "Medium" : "Средний"; }
    public static String voiceMode() { return lang == EN ? "Voice mode:" : "Режим голоса:"; }
    public static String voiceHoldStar() { return lang == EN ? "Hold *" : "Зажать *"; }
    public static String voiceOkButton() { return lang == EN ? "OK button" : "Кнопка OK"; }

    // Серверы
    public static String servers() { return lang == EN ? "Servers" : "Серверы"; }
    public static String edit() { return lang == EN ? "Edit" : "Изменить"; }
    public static String serverSwitched() { return lang == EN ? "server:" : "сервер:"; }
    public static String serverOk() { return lang == EN ? "connected" : "на связи"; }
    public static String serverBad() { return lang == EN ? "no connection" : "не отвечает"; }
    public static String tgOnly() { return lang == EN ? "Telegram only" : "только Телеграм"; }
    public static String claudeOffHere() {
        return lang == EN ? "Telegram-only server. Open Claude from Home (key 1)."
                          : "Это сервер Телеграма. Клод - с главного экрана (клавиша 1).";
    }

    // Telegram
    public static String telegram() { return lang == EN ? "Telegram" : "Телеграм"; }
    public static String contacts() { return lang == EN ? "Contacts" : "Контакты"; }
    public static String noContacts() { return lang == EN ? "No contacts yet" : "Нет контактов"; }
    public static String tgInviteHow() {
        return lang == EN ? "Share this bot, they write it:" : "Дай друзьям бота, пусть напишут:";
    }
    public static String addContact() { return lang == EN ? "Add" : "Добавить"; }
    public static String refresh() { return lang == EN ? "Refresh" : "Обновить"; }
    public static String tgSent() { return lang == EN ? "sent" : "отправлено"; }
    public static String tgVoiceSent() { return lang == EN ? "voice sent" : "голосовое отправлено"; }
    public static String tgOff() {
        return lang == EN ? "Telegram off: no bot token on server"
                          : "Телеграм выкл: на сервере нет токена бота";
    }
    public static String tgHint1() { return lang == EN ? "Send - type message" : "Отпр. - набрать текст"; }
    public static String tgHint2() { return lang == EN ? "hold * - voice message" : "зажми * - голосовое"; }
    public static String tgHint3() { return lang == EN ? "Menu - refresh chat, contacts" : "Menu - обновить чат, контакты"; }
    public static String tgNoMessages() { return lang == EN ? "no messages yet" : "переписки пока нет"; }
    public static String tgNewMessage() { return lang == EN ? "new Telegram message" : "новое сообщение в Телеграм"; }
    public static String tgReplyHint() { return lang == EN ? "reply: press 5" : "ответить: нажми 5"; }
    public static String updateAvailable() { return lang == EN ? "available: Menu > Update" : "доступна: Menu > Обновить"; }
    public static String chatId() { return lang == EN ? "Chat ID:" : "ID чата:"; }
    public static String name() { return lang == EN ? "Name:" : "Имя:"; }

    // Главный экран
    public static String claudeTile() { return lang == EN ? "Claude" : "Клод"; }
    public static String settingsFull() { return lang == EN ? "Settings" : "Настройки"; }
    public static String sessionsSub() { return lang == EN ? "Claude & OpenCode chats" : "чаты Клода и OpenCode"; }
    public static String settingsSub() { return lang == EN ? "language, voice, servers" : "язык, голос, серверы"; }
    public static String noNews() { return lang == EN ? "no new messages" : "нет новых"; }
    public static String home() { return lang == EN ? "Home" : "Домой"; }
    public static String anyKeyBack() { return lang == EN ? "any key - back" : "любая клавиша - назад"; }
    public static String params() { return lang == EN ? "Options" : "Параметры"; }
    public static String homeHelp1() { return lang == EN ? "up/down - choose, OK - open" : "вверх/вниз - выбор, OK - открыть"; }
    public static String homeHelp2() { return lang == EN ? "1 TG  2 Claude  3 OpenCode" : "1 ТГ  2 Клод  3 OpenCode"; }
    public static String homeHelp3() { return lang == EN ? "4 sessions  7 settings" : "4 сессии  7 настройки"; }
    public static String homeHelp4() { return lang == EN ? "in chat: 1 - home" : "в чате: 1 - домой"; }
    public static String homeHelp5() { return lang == EN ? "* - snake game" : "* - змейка"; }

    // Привязка телефона
    public static String pairTitle() { return lang == EN ? "Phone is not linked yet" : "Телефон ещё не привязан"; }
    public static String pairLost() { return lang == EN ? "Link lost - link again" : "Привязка слетела - привяжи заново"; }
    public static String pairStep1() { return lang == EN ? "5 - get a code" : "5 - получить код"; }
    public static String pairStep2() { return lang == EN ? "then type the 6 digits" : "потом набери 6 цифр"; }
    public static String pairStep3() { return lang == EN ? "once - then it just works" : "один раз - дальше само"; }
    public static String pairCode() { return lang == EN ? "Code:" : "Код:"; }
    public static String pairCodeSent() { return lang == EN ? "code issued, type it" : "код выдан, набери его"; }
    public static String pairBadCode() { return lang == EN ? "code rejected" : "код не принят"; }
    public static String pairDone() { return lang == EN ? "linked" : "привязано"; }
    public static String keyRotate() { return lang == EN ? "New key" : "Новый ключ"; }
    public static String keyRotated() { return lang == EN ? "key replaced" : "ключ заменён"; }
    public static String keySecondFailed() { return lang == EN ? "second server not updated - tell Claude" : "второй сервер не обновлён - скажи Клоду"; }
    public static String relink() { return lang == EN ? "Link again" : "Привязать заново"; }

    // Settings labels
    public static String model() { return lang == EN ? "Model:" : "Модель:"; }
    public static String language() { return lang == EN ? "Language:" : "Язык:"; }
    public static String server() { return lang == EN ? "Server:" : "Сервер:"; }
}
