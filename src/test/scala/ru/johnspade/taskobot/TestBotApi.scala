package ru.johnspade.taskobot

import cats.syntax.option.*
import com.dimafeng.testcontainers.MockServerContainer
import org.http4s.blaze.client.BlazeClientBuilder
import org.mockserver.client.MockServerClient
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.JsonBody
import ru.johnspade.taskobot.TelegramBotApi.TelegramBotApi
import ru.johnspade.taskobot.TestUsers.*
import ru.johnspade.taskobot.core.TelegramOps.inlineKeyboardButton
import ru.johnspade.taskobot.core.*
import ru.johnspade.taskobot.messages.Language
import ru.johnspade.tgbot.messageentities.TypedMessageEntity
import ru.johnspade.tgbot.messageentities.TypedMessageEntity.Plain.lineBreak
import ru.johnspade.tgbot.messageentities.TypedMessageEntity.*
import telegramium.bots.*
import telegramium.bots.client.Method
import telegramium.bots.high.*
import telegramium.bots.high.keyboards.*
import zio.*
import zio.interop.catz.*

import java.time.Instant
import java.time.LocalDate

object TestBotApi:
  private val mockServerContainer: ULayer[MockServerContainer] =
    ZLayer.scoped {
      ZIO.acquireRelease {
        ZIO.attemptBlocking {
          val container = MockServerContainer("5.15.0")
          container.start()
          container
        }.orDie
      }(container => ZIO.attemptBlocking(container.stop()).orDie)
    }

  private val mockServerClient: URLayer[MockServerContainer, MockServerClient] =
    ZLayer(
      ZIO
        .service[MockServerContainer]
        .flatMap { mockServer =>
          ZIO
            .attemptBlocking(new MockServerClient("localhost", mockServer.serverPort))
            // .tap(client => ZIO.attemptBlocking(client.openUI()))
            .orDie
        }
    )

  private val api: URLayer[MockServerContainer, TelegramBotApi] = ZLayer.scoped {
    (for
      mockServer <- ZIO.service[MockServerContainer]
      httpClient <- BlazeClientBuilder[Task].resource.toScopedZIO
      botApi = BotApi[Task](httpClient, mockServer.endpoint)
    yield botApi).orDie
  }

  val testApiLayer: ULayer[MockServerContainer with MockServerClient with TelegramBotApi] =
    ZLayer.make[MockServerContainer with MockServerClient with TelegramBotApi](
      mockServerContainer,
      mockServerClient,
      api
    )

  def createMock[Res](method: Method[Res], responseBody: String): RIO[MockServerClient, Unit] =
    ZIO
      .service[MockServerClient]
      .flatMap { client =>
        ZIO.attemptBlocking(
          client
            .when(
              request("/" + method.payload.name)
                .withMethod("POST")
                .withBody(new JsonBody(method.payload.json.toString))
            )
            .respond(response().withBody(responseBody))
        )
      }

  object Mocks:
    val messageResponse: String =
      """
        {
          "ok": true,
          "result": {
            "message_id": 0,
            "date": 1593365356,
            "chat": {
            "id": 0,
            "type": "private"
          },
            "text": "Lorem ipsum"
          }
        }
      """

    val listLanguages: Method[Either[Boolean, Message]] =
      Methods.editMessageText(
        "Current language: English",
        ChatIntId(johnChatId).some,
        messageId = 0.some,
        replyMarkup = InlineKeyboardMarkups
          .singleColumn(
            List(
              inlineKeyboardButton("English", Ignore),
              inlineKeyboardButton("Русский", SetLanguage(Language.Russian)),
              inlineKeyboardButton("Turkish", SetLanguage(Language.Turkish)),
              inlineKeyboardButton("Italian", SetLanguage(Language.Italian)),
              inlineKeyboardButton("Traditional Chinese", SetLanguage(Language.TraditionalChinese)),
              inlineKeyboardButton("Spanish", SetLanguage(Language.Spanish))
            )
          )
          .some
      )

    val listLanguagesRussian: Method[Either[Boolean, Message]] =
      Methods.editMessageText(
        "Текущий язык: Русский",
        ChatIntId(johnChatId).some,
        messageId = 0.some,
        replyMarkup = InlineKeyboardMarkups
          .singleColumn(
            List(
              inlineKeyboardButton("English", SetLanguage(Language.English)),
              inlineKeyboardButton("Русский", Ignore),
              inlineKeyboardButton("Turkish", SetLanguage(Language.Turkish)),
              inlineKeyboardButton("Italian", SetLanguage(Language.Italian)),
              inlineKeyboardButton("Traditional Chinese", SetLanguage(Language.TraditionalChinese)),
              inlineKeyboardButton("Spanish", SetLanguage(Language.Spanish))
            )
          )
          .some
      )

    val languageChangedMessage: Method[Message] =
      Methods.sendMessage(
        ChatIntId(johnChatId),
        text = "Язык изменен",
        replyMarkup = ReplyKeyboardMarkup(
          List(
            List(KeyboardButtons.text("\uD83D\uDCCB Задачи"), KeyboardButtons.text("➕ Новая личная задача")),
            List(KeyboardButtons.text("\uD83D\uDE80 Новая совместная задача"), KeyboardButtons.text("❓ Справка")),
            List(
              KeyboardButtons.text("⚙️ Настройки"),
              KeyboardButton("🌍 Часовой пояс", webApp = Some(WebAppInfo("https://timezones.johnspade.ru")))
            )
          ),
          resizeKeyboard = true.some
        ).some
      )

    def taskCreatedMessage(text: String): Method[Message] =
      Methods.sendMessage(
        ChatIntId(johnChatId),
        text,
        replyMarkup = ReplyKeyboardMarkup(
          List(
            List(KeyboardButtons.text("\uD83D\uDCCB Tasks"), KeyboardButtons.text("➕ New personal task")),
            List(KeyboardButtons.text("\uD83D\uDE80 New collaborative task"), KeyboardButtons.text("❓ Help")),
            List(
              KeyboardButtons.text("⚙️ Settings"),
              KeyboardButton("🌍 Timezone", webApp = Some(WebAppInfo("https://timezones.johnspade.ru")))
            )
          ),
          resizeKeyboard = true.some
        ).some
      )

    val removeReplyMarkup: Method[Either[Boolean, Message]] =
      Methods.editMessageReplyMarkup(inlineMessageId = "0".some)

    val addConfirmButton: Method[Either[Boolean, Message]] =
      Methods.editMessageReplyMarkup(
        inlineMessageId = "0".some,
        replyMarkup = InlineKeyboardMarkups
          .singleButton(
            inlineKeyboardButton("Confirm task", ConfirmTask(1L.some, 1337L.some))
          )
          .some
      )

    val editMessageTextList: Method[Either[Boolean, Message]] =
      Methods.editMessageText(
        "Chat: John\n1. Buy some milk – John\n",
        ChatIntId(kaitrinChatId).some,
        messageId = 0.some,
        entities = TypedMessageEntity.toMessageEntities(
          List(
            plain"Chat: ",
            bold"John",
            lineBreak,
            plain"1. Buy some milk",
            italic" – John",
            lineBreak
          )
        ),
        replyMarkup = InlineKeyboardMarkups
          .singleColumn(
            List(
              inlineKeyboardButton("1", TaskDetails(1L, 0)),
              inlineKeyboardButton("Chat list", Chats(0))
            )
          )
          .some
      )

    def editMessageTextCheckTask(chatId: Int): Method[Either[Boolean, Message]] =
      Methods.editMessageText(
        "Chat: John\n",
        ChatIntId(chatId).some,
        messageId = 0.some,
        entities = TypedMessageEntity.toMessageEntities(
          List(
            plain"Chat: ",
            bold"John",
            lineBreak
          )
        ),
        replyMarkup = InlineKeyboardMarkups.singleButton(inlineKeyboardButton("Chat list", Chats(0))).some
      )

    val taskCompletedMessage: Method[Message] =
      Methods.sendMessage(ChatIntId(kaitrinChatId), "Task \"Buy some milk\" has been marked completed by Kaitrin.")

    val editMessageTextPersonalTasks: Method[Either[Boolean, Message]] =
      Methods.editMessageText(
        "Chat: Personal tasks\n",
        ChatIntId(johnChatId).some,
        messageId = 0.some,
        entities = TypedMessageEntity.toMessageEntities(
          List(
            plain"Chat: ",
            bold"Personal tasks",
            lineBreak
          )
        ),
        replyMarkup = InlineKeyboardMarkups.singleButton(inlineKeyboardButton("Chat list", Chats(0))).some
      )

    val editMessageTextChatsSinglePage: Method[Either[Boolean, Message]] =
      Methods.editMessageText(
        chatId = ChatIntId(0).some,
        messageId = 0.some,
        text = "Chats with tasks",
        replyMarkup = InlineKeyboardMarkups
          .singleColumn(
            List.tabulate(5)(n => inlineKeyboardButton(n.toString, Tasks(0, n.toLong))) ++
              List(InlineKeyboardButtons.url("Buy me a coffee ☕", "https://buymeacoff.ee/johnspade"))
          )
          .some
      )

    val editMessageTextChatsMultiplePages: Method[Either[Boolean, Message]] =
      Methods.editMessageText(
        chatId = ChatIntId(0).some,
        messageId = 0.some,
        text = "Chats with tasks",
        replyMarkup = InlineKeyboardMarkups
          .singleColumn(
            List.tabulate(5) { n =>
              val id = n + 5
              inlineKeyboardButton(id.toString, Tasks(0, id.toLong))
            } ++ List(
              inlineKeyboardButton("Previous page", Chats(0)),
              inlineKeyboardButton("Next page", Chats(2))
            ) ++
              List(InlineKeyboardButtons.url("Buy me a coffee ☕", "https://buymeacoff.ee/johnspade"))
          )
          .some
      )

    def editMessageTextTasksSinglePage(taskId: Long): Method[Either[Boolean, Message]] =
      Methods.editMessageText(
        chatId = ChatIntId(0).some,
        messageId = 0.some,
        text = "Chat: Kaitrin\n1. Wash dishes please – John\n",
        entities = TypedMessageEntity.toMessageEntities(
          List(
            plain"Chat: ",
            bold"Kaitrin",
            lineBreak,
            plain"1. Wash dishes please",
            italic" – John",
            lineBreak
          )
        ),
        replyMarkup = InlineKeyboardMarkups
          .singleColumn(
            List(
              inlineKeyboardButton("1", TaskDetails(taskId, 0)),
              inlineKeyboardButton("Chat list", Chats(0))
            )
          )
          .some
      )

    val editMessageTextTasksMultiplePages: Method[Either[Boolean, Message]] =
      Methods.editMessageText(
        chatId = ChatIntId(0).some,
        messageId = 0.some,
        text = "Chat: Kaitrin\n1. 5 – John\n2. 6 – John\n3. 7 – John\n4. 8 – John\n5. 9 – John\n",
        entities = TypedMessageEntity.toMessageEntities(
          List(
            plain"Chat: ",
            bold"Kaitrin",
            lineBreak,
            plain"1. 5",
            italic" – John",
            lineBreak,
            plain"2. 6",
            italic" – John",
            lineBreak,
            plain"3. 7",
            italic" – John",
            lineBreak,
            plain"4. 8",
            italic" – John",
            lineBreak,
            plain"5. 9",
            italic" – John",
            lineBreak
          )
        ),
        replyMarkup = InlineKeyboardMarkup(
          List(
            List(
              inlineKeyboardButton("1", TaskDetails(23L, 1)),
              inlineKeyboardButton("2", TaskDetails(24L, 1)),
              inlineKeyboardButton("3", TaskDetails(25L, 1)),
              inlineKeyboardButton("4", TaskDetails(26L, 1)),
              inlineKeyboardButton("5", TaskDetails(27L, 1))
            ),
            List(inlineKeyboardButton("Previous page", Tasks(0, kaitrin.id))),
            List(inlineKeyboardButton("Next page", Tasks(2, kaitrin.id))),
            List(inlineKeyboardButton("Chat list", Chats(0)))
          )
        ).some
      )

    val editMessageTextTasksKaitrin: Method[Either[Boolean, Message]] =
      Methods.editMessageText(
        "Chat: Kaitrin\n",
        ChatIntId(0).some,
        messageId = 0.some,
        entities = TypedMessageEntity.toMessageEntities(
          List(
            plain"Chat: ",
            bold"Kaitrin",
            lineBreak
          )
        ),
        replyMarkup = InlineKeyboardMarkups.singleButton(inlineKeyboardButton("Chat list", Chats(0))).some
      )

    val taskCompletedByJohnMessage: Method[Message] =
      Methods.sendMessage(
        ChatIntId(kaitrinChatId),
        """Task "Buy some milk" has been marked completed by John."""
      )

    val taskCompletedByKaitrinMessage: Method[Message] =
      Methods.sendMessage(
        ChatIntId(johnChatId),
        """Task "Buy some milk" has been marked completed by Kaitrin."""
      )

    def editMessageTextTaskDetails(taskId: Long, now: Instant): Method[Either[Boolean, Message]] =
      val markup = InlineKeyboardMarkup(
        List(
          List(inlineKeyboardButton("✅", CheckTask(0, taskId))),
          List(
            inlineKeyboardButton(
              "📅",
              DatePicker(taskId, LocalDate.ofInstant(now, UTC))
            ),
            inlineKeyboardButton("🕒", TimePicker(taskId))
          ),
          List(
            inlineKeyboardButton(
              "Tasks",
              Tasks(0, kaitrin.id)
            )
          )
        )
      )
      editMessageTextTaskDetailsMock(taskId, "Buy some milk", markup)

    def editMessageTextTaskDetailsStandardReminders(taskId: Long): Method[Either[Boolean, Message]] =
      val markup = InlineKeyboardMarkups
        .singleColumn(
          List(
            inlineKeyboardButton("At start", CreateReminder(taskId, 0)),
            inlineKeyboardButton("10m before", CreateReminder(taskId, 10)),
            inlineKeyboardButton("30m before", CreateReminder(taskId, 30)),
            inlineKeyboardButton("1h before", CreateReminder(taskId, 60)),
            inlineKeyboardButton("2h before", CreateReminder(taskId, 60 * 2)),
            inlineKeyboardButton("1d before", CreateReminder(taskId, 60 * 24)),
            inlineKeyboardButton("2d before", CreateReminder(taskId, 60 * 24 * 2)),
            inlineKeyboardButton("3d before", CreateReminder(taskId, 60 * 24 * 3)),
            inlineKeyboardButton("Back", Reminders(taskId, 0))
          )
        )
      editMessageTextTaskDetailsMock(taskId, "Buy some milk", markup)

    def editMessageTextTaskDetailsReminders(taskId: Long, reminderId: Long): Method[Either[Boolean, Message]] =
      val markup = InlineKeyboardMarkup(
        List(
          List(inlineKeyboardButton("🔔 1h  before", RemoveReminder(reminderId, taskId))),
          List(
            inlineKeyboardButton("Add", StandardReminders(taskId, 0)),
            inlineKeyboardButton("Back", TaskDetails(taskId, 0))
          )
        )
      )
      editMessageTextTaskDetailsMock(taskId, "Buy some milk", markup, "1970-01-01 00:00".some)

    def editMessageTextTaskDetailsNoReminders(taskId: Long): Method[Either[Boolean, Message]] =
      val markup = InlineKeyboardMarkup(
        List(
          List(
            inlineKeyboardButton("Add", StandardReminders(taskId, 0)),
            inlineKeyboardButton("Back", TaskDetails(taskId, 0))
          )
        )
      )
      editMessageTextTaskDetailsMock(taskId, "Buy some milk", markup, "1970-01-01 00:00".some)

    def editMessageTextTaskDetailsNoRemindersNoDeadline(taskId: Long): Method[Either[Boolean, Message]] =
      val markup = InlineKeyboardMarkup(
        List(
          List(
            inlineKeyboardButton("Add", StandardReminders(taskId, 0)),
            inlineKeyboardButton("Back", TaskDetails(taskId, 0))
          )
        )
      )
      editMessageTextTaskDetailsMock(taskId, "Buy some milk", markup)

    def editMessageTextTaskDetailsThreeReminders(
        taskId: Long,
        reminder1Id: Long,
        reminder2Id: Long,
        reminder3Id: Long
    ): Method[Either[Boolean, Message]] =
      val markup = InlineKeyboardMarkup(
        List(
          List(inlineKeyboardButton("🔔 At start", RemoveReminder(reminder1Id, taskId))),
          List(inlineKeyboardButton("🔔 1m  before", RemoveReminder(reminder2Id, taskId))),
          List(inlineKeyboardButton("🔔 10m  before", RemoveReminder(reminder3Id, taskId))),
          List(
            inlineKeyboardButton("Add", StandardReminders(taskId, 0)),
            inlineKeyboardButton("Back", TaskDetails(taskId, 0))
          )
        )
      )
      editMessageTextTaskDetailsMock(taskId, "Buy some milk", markup, "1970-01-01 00:00".some)

    def editMessageTextTaskDeadlineUpdated(taskId: Long, now: Instant): Method[Either[Boolean, Message]] =
      val markup = InlineKeyboardMarkup(
        List(
          List(inlineKeyboardButton("✅", CheckTask(0, taskId))),
          List(
            inlineKeyboardButton(
              "📅",
              DatePicker(taskId, LocalDate.ofInstant(now, UTC))
            ),
            inlineKeyboardButton("🕒", TimePicker(taskId))
          ),
          List(
            inlineKeyboardButton(
              "Tasks",
              Tasks(0, kaitrin.id)
            )
          )
        )
      )
      editMessageTextTaskDetailsMock(taskId, "Buy some milk", markup, "1970-01-01 00:00".some)

    def editMessageTextTaskDeadlineRemoved(taskId: Long, now: Instant): Method[Either[Boolean, Message]] =
      val markup = InlineKeyboardMarkup(
        List(
          List(inlineKeyboardButton("✅", CheckTask(0, taskId))),
          List(
            inlineKeyboardButton(
              "📅",
              DatePicker(taskId, LocalDate.ofInstant(now, UTC))
            ),
            inlineKeyboardButton("🕒", TimePicker(taskId))
          ),
          List(
            inlineKeyboardButton(
              "Tasks",
              Tasks(0, kaitrin.id)
            )
          )
        )
      )
      editMessageTextTaskDetailsMock(taskId, "Buy some milk", markup)

    def editMessageTextTimePicker(taskId: Long, now: Instant): Method[Either[Boolean, Message]] =
      val markup = InlineKeyboardMarkup(
        List(
          List(inlineKeyboardButton("✅", CheckTask(0, taskId))),
          List(
            inlineKeyboardButton(
              "📅",
              DatePicker(taskId, LocalDate.ofInstant(now, UTC))
            ),
            inlineKeyboardButton("🕒", TimePicker(taskId))
          ),
          List(
            inlineKeyboardButton(
              "Tasks",
              Tasks(0, kaitrin.id)
            )
          )
        )
      )
      editMessageTextTaskDetailsMock(taskId, "Buy some milk", markup, "1970-01-01 13:15".some)

    def sendMessageReminder(
        text: String,
        taskId: Long,
        now: Instant,
        dueDate: String = "1970-01-01 00:01"
    ): Method[Message] =
      Methods.sendMessage(
        ChatIntId(johnChatId),
        s"""|🔔 $text
            |
            |🕒 Due date: $dueDate
            |
            |Created at: 1970-01-01 00:00""".stripMargin,
        entities = TypedMessageEntity.toMessageEntities(
          List(
            plain"🔔 $text",
            lineBreak,
            lineBreak,
            bold"🕒 Due date: $dueDate",
            lineBreak,
            lineBreak,
            italic"Created at: 1970-01-01 00:00"
          )
        ),
        replyMarkup = InlineKeyboardMarkup(
          List(
            List(inlineKeyboardButton("✅", CheckTask(0, taskId))),
            List(
              inlineKeyboardButton(
                "📅",
                DatePicker(taskId, LocalDate.ofInstant(now, UTC))
              ),
              inlineKeyboardButton("🕒", TimePicker(taskId))
            ),
            List(
              inlineKeyboardButton(
                "Tasks",
                Tasks(0, kaitrin.id)
              )
            )
          )
        ).some
      )

    private def editMessageTextTaskDetailsMock(
        taskId: Long,
        text: String,
        replyMarkup: InlineKeyboardMarkup,
        dueDate: Option[String] = None
    ) =
      Methods.editMessageText(
        s"""|$text
            |
            |🕒 Due date: ${dueDate.getOrElse("-")}
            |
            |Created at: 1970-01-01 00:00""".stripMargin,
        ChatIntId(0).some,
        messageId = 0.some,
        entities = TypedMessageEntity.toMessageEntities(
          List(
            Plain(text),
            lineBreak,
            lineBreak,
            bold"🕒 Due date: ${dueDate.getOrElse("-")}",
            lineBreak,
            lineBreak,
            italic"Created at: 1970-01-01 00:00"
          )
        ),
        replyMarkup = replyMarkup.some
      )
  end Mocks
