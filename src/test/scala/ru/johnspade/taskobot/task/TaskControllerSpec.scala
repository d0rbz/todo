package ru.johnspade.taskobot.task

import cats.syntax.option._
import org.mockito.captor.ArgCaptor
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import ru.johnspade.taskobot.TestAssertions.isMethodsEqual
import ru.johnspade.taskobot.TestEnvironments.PostgresITEnv
import ru.johnspade.taskobot.core.TelegramOps.inlineKeyboardButton
import ru.johnspade.taskobot.core.TypedMessageEntity._
import ru.johnspade.taskobot.core.callbackqueries.{CallbackQueryData, ContextCallbackQuery}
import ru.johnspade.taskobot.core.{CbData, Chats, CheckTask, ConfirmTask, Tasks, TypedMessageEntity}
import ru.johnspade.taskobot.i18n.Language
import ru.johnspade.taskobot.tags.PageNumber
import ru.johnspade.taskobot.task.TaskController.TaskController
import ru.johnspade.taskobot.task.TaskRepository.TaskRepository
import ru.johnspade.taskobot.task.tags.{CreatedAt, Done, TaskId, TaskText}
import ru.johnspade.taskobot.user.UserRepository.UserRepository
import ru.johnspade.taskobot.user.tags.{ChatId, FirstName, UserId}
import ru.johnspade.taskobot.user.{User, UserRepository}
import ru.johnspade.taskobot.{BotService, TestEnvironments}
import telegramium.bots.client.Method
import telegramium.bots.high.{Api, InlineKeyboardMarkup, Methods}
import telegramium.bots.{CallbackQuery, Chat, ChatIntId, Message, User => TgUser}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.test.Assertion.{equalTo, hasField, isNone, isSome}
import zio.test.TestAspect.{before, sequential}
import zio.test._
import zio.test.environment.TestEnvironment
import zio.{Task, URLayer, ZIO, ZLayer, clock}

object TaskControllerSpec extends DefaultRunnableSpec with MockitoSugar with ArgumentMatchersSugar {
  override def spec: ZSpec[TestEnvironment, Throwable] = (suite("TaskControllerSpec")(
    suite("Chats")(
      testM("should list chats as a single page") {
        for {
          _ <- createUsersAndTasks(5)
          reply <- callUserRoute(Chats(firstPage), johnTg)
          chatsReplyAssertions = assert(reply)(isSome(equalTo(Methods.answerCallbackQuery("0"))))
          listChatsAssertions = verifyMethodCall(Methods.editMessageText(
            chatId = ChatIntId(0).some,
            messageId = 0.some,
            text = "Chats with tasks",
            replyMarkup = InlineKeyboardMarkup.singleColumn(
              List.tabulate(5)(n => inlineKeyboardButton(n.toString, Tasks(UserId(n), firstPage)))
            ).some
          ))
        } yield chatsReplyAssertions && listChatsAssertions
      },

      testM("should list chats as multiple pages") {
        for {
          _ <- createUsersAndTasks(11)
          reply <- callUserRoute(Chats(PageNumber(1)), johnTg)
          replyAssertions = assert(reply)(isSome(equalTo(Methods.answerCallbackQuery("0"))))
          listChatsAssertions = verifyMethodCall(Methods.editMessageText(
            chatId = ChatIntId(0).some,
            messageId = 0.some,
            text = "Chats with tasks",
            replyMarkup = InlineKeyboardMarkup.singleColumn(
              List.tabulate(5) { n =>
                val id = n + 5
                inlineKeyboardButton(id.toString, Tasks(UserId(id), firstPage))
              } ++ List(
                inlineKeyboardButton("Previous page", Chats(PageNumber(0))),
                inlineKeyboardButton("Next page", Chats(PageNumber(2)))
              )
            ).some
          ))
        } yield replyAssertions && listChatsAssertions
      }
    ),

    suite("Tasks")(
      testM("should list tasks as a single page") {
        for {
          task <- createTask("Wash dishes please", kaitrin.id.some)
          reply <- callUserRoute(Tasks(kaitrin.id, firstPage), johnTg)
          replyAssertions = assert(reply)(isSome(equalTo(Methods.answerCallbackQuery("0"))))
          listTasksAssertions = verifyMethodCall(Methods.editMessageText(
            chatId = ChatIntId(0).some,
            messageId = 0.some,
            text = "Chat: Kaitrin\\n1. Wash dishes please– Kaitrin\\n\\nSelect the task number to mark it as completed.",
            entities = TypedMessageEntity.toMessageEntities(List(
              plain"Chat: ", bold"Kaitrin", plain"\n",
              plain"1. Wash dishes please", italic"– Kaitrin", plain"\n",
              plain"\n", italic"Select the task number to mark it as completed."
            )),
            replyMarkup = InlineKeyboardMarkup.singleColumn(List(
              inlineKeyboardButton("1", CheckTask(task.id, firstPage, kaitrin.id)),
              inlineKeyboardButton("Chat list", Chats(firstPage))
            )).some
          ))
        } yield replyAssertions && listTasksAssertions
      },

      testM("should list tasks as multiple pages") {
        for {
          _ <- createKaitrinTasks(11)
          reply <- callUserRoute(Tasks(kaitrin.id, PageNumber(1)), johnTg)
          replyAssertions = assert(reply)(isSome(equalTo(Methods.answerCallbackQuery("0"))))
          listTasksAssertions = verifyMethodCall(Methods.editMessageText(
            chatId = ChatIntId(0).some,
            messageId = 0.some,
            text = "Chat: Kaitrin\\n1. 5– Kaitrin\\n2. 6– Kaitrin\\n3. 7– Kaitrin\\n4. 8– Kaitrin\\n5. 9– Kaitrin\\n\\nSelect the task number to mark it as completed.",
            entities = TypedMessageEntity.toMessageEntities(List(
              plain"Chat: ", bold"Kaitrin", plain"\n",
              plain"1. 5", italic"– Kaitrin", plain"\n",
              plain"2. 6", italic"– Kaitrin", plain"\n",
              plain"3. 7", italic"– Kaitrin", plain"\n",
              plain"4. 8", italic"– Kaitrin", plain"\n",
              plain"5. 9", italic"– Kaitrin", plain"\n",
              plain"\n", italic"Select the task number to mark it as completed."
            )),
            replyMarkup = InlineKeyboardMarkup(List(
              List(
                inlineKeyboardButton("1", CheckTask(TaskId(23L), PageNumber(1), kaitrin.id)),
                inlineKeyboardButton("2", CheckTask(TaskId(24L), PageNumber(1), kaitrin.id)),
                inlineKeyboardButton("3", CheckTask(TaskId(25L), PageNumber(1), kaitrin.id)),
                inlineKeyboardButton("4", CheckTask(TaskId(26L), PageNumber(1), kaitrin.id)),
                inlineKeyboardButton("5", CheckTask(TaskId(27L), PageNumber(1), kaitrin.id))
              ),
              List(inlineKeyboardButton("Previous page", Tasks(kaitrin.id, firstPage))),
              List(inlineKeyboardButton("Next page", Tasks(kaitrin.id, PageNumber(2)))),
              List(inlineKeyboardButton("Chat list", Chats(firstPage)))
            )).some
          ))
        } yield replyAssertions && listTasksAssertions
      }
    ),

    suite("CheckTask")(
      testM("should check a task") {
        for {
          task <- createTask("Buy some milk", kaitrin.id.some)
          reply <- callUserRoute(CheckTask(task.id, firstPage, kaitrin.id), johnTg)
          checkedTask <- TaskRepository.findById(task.id)
          checkedTaskAssertions = assert(checkedTask.get)(hasField("done", _.done, equalTo(Done(true)))) &&
            assert(checkedTask.get)(hasField("doneAt", _.doneAt, isSome))
          replyAssertions = assert(reply)(isSome(equalTo(
            Methods.answerCallbackQuery("0", "Task has been marked as completed.".some)
          )))
          _ <- ZIO.effect(Thread.sleep(1000))
          listTasksAssertions = verifyMethodCall(Methods.editMessageText(
            ChatIntId(0).some,
            messageId = 0.some,
            text = "Chat: Kaitrin\\n\\nSelect the task number to mark it as completed.",
            entities = TypedMessageEntity.toMessageEntities(List(
              plain"Chat: ", bold"Kaitrin", plain"\n",
              plain"\n", italic"Select the task number to mark it as completed."
            )),
            replyMarkup = InlineKeyboardMarkup.singleButton(inlineKeyboardButton("Chat list", Chats(firstPage))).some
          ))
          notifyAssertions = verifyMethodCall(Methods.sendMessage(
            ChatIntId(kaitrinChatId),
            """Task "Buy some milk" has been marked as completed by John."""
          ))
        } yield replyAssertions && checkedTaskAssertions && listTasksAssertions && notifyAssertions
      }
    ),

    suite("ConfirmTask")(
      testM("receiver should be able to confirm task") {
        for {
          task <- createTask("Buy some milk")
          reply <- confirmTask(ConfirmTask(john.id, task.id.some), kaitrinTg)
          confirmedTask <- TaskRepository.findById(task.id)
          confirmedTaskAssertions = assert(confirmedTask.get.receiver)(isSome(equalTo(kaitrin.id)))
          _ <- ZIO.effect(Thread.sleep(1000))
          removeMarkupAssertions = verifyMethodCall(Methods.editMessageReplyMarkup(inlineMessageId = "0".some, replyMarkup = None))
          confirmTaskReplyAssertions = assert(reply)(isSome(equalTo(Methods.answerCallbackQuery("0"))))
        } yield confirmedTaskAssertions && removeMarkupAssertions && confirmTaskReplyAssertions
      },

      testM("sender should not be able to confirm task") {
        for {
          task <- createTask("Buy groceries")
          reply <- confirmTask(ConfirmTask(john.id, task.id.some), johnTg)
          unconfirmedTask <- TaskRepository.findById(task.id)
          confirmTaskReplyAssertions = assert(reply)(isSome(equalTo(
            Methods.answerCallbackQuery("0", "The task must be confirmed by the receiver".some)
          )))
          unconfirmedTaskAssertions = assert(unconfirmedTask.get.receiver)(isNone)
        } yield confirmTaskReplyAssertions && unconfirmedTaskAssertions
      },

      testM("cannot confirm task with wrong senderId") {
        for {
          task <- createTask("Buy some bread")
          bobId = UserId(0)
          reply <- confirmTask(ConfirmTask(bobId, task.id.some), TgUser(bobId, isBot = false, "Bob"))
          unconfirmedTask <- TaskRepository.findById(task.id)
          confirmTaskReplyAssertions = assert(reply)(isSome(equalTo(Methods.answerCallbackQuery("0"))))
          unconfirmedTaskAssertions = assert(unconfirmedTask.get.receiver)(isNone)
        } yield confirmTaskReplyAssertions && unconfirmedTaskAssertions
      }
    )
  )
    @@ sequential
    @@ before {
    for {
      _ <- UserRepository.createOrUpdate(john)
      _ <- UserRepository.createOrUpdate(kaitrin)
    } yield ()
  }
    @@ TestAspect.after {
    for {
      _ <- TaskRepository.clear()
      _ <- UserRepository.clear()
    } yield ()
  })
    .provideCustomLayerShared(TestEnvironment.env)

  private val botApiMock = mock[Api[Task]]
  when(botApiMock.execute[Message](*)).thenReturn(Task.succeed(mockMessage()))
  when(botApiMock.execute[Either[Boolean, Message]](*)).thenReturn(Task.right(mockMessage()))

  private val johnId = 1337
  private val johnTg = TgUser(johnId, isBot = false, "John")
  private val kaitrinId = 911
  private val kaitrinTg = TgUser(kaitrinId, isBot = false, "Kaitrin")

  private val john = User(UserId(johnId), FirstName(johnTg.firstName), Language.English)
  private val kaitrinChatId = ChatId(17L)
  private val kaitrin = User(UserId(kaitrinId), FirstName(kaitrinTg.firstName), Language.English, kaitrinChatId.some)

  private val firstPage = PageNumber(0)

  private def mockMessage(chatId: Int = 0) =
    Message(0, date = 0, chat = Chat(chatId, `type` = ""), from = TgUser(id = 123, isBot = true, "Taskobot").some)

  private def callbackQuery(data: CbData, from: TgUser, inlineMessageId: Option[String] = None) =
    CallbackQuery("0", from, chatInstance = "", data = data.toCsv.some, message = mockMessage().some, inlineMessageId = inlineMessageId)

  private def verifyMethodCall[Res](method: Method[Res]) = {
    val captor = ArgCaptor[Method[Res]]
    verify(botApiMock, atLeastOnce).execute(captor).asInstanceOf[Unit]
    assert(captor.values)(Assertion.exists(isMethodsEqual(method)))
  }

  private def createTask(text: String, receiver: Option[UserId] = None) =
    for {
      now <- clock.instant
      task <- TaskRepository.create(
        NewTask(UserId(johnId), TaskText(text), CreatedAt(now.toEpochMilli), receiver)
      )
    } yield task

  private def createUsersAndTasks(count: Int) =
    for {
      now <- clock.instant
      usersAndTasks = List.tabulate(count) { n =>
        val user = User(UserId(n), FirstName(n.toString), Language.English)
        user -> NewTask(john.id, TaskText(n.toString), CreatedAt(now.toEpochMilli), user.id.some)
      }
      _ <- ZIO.foreach_(usersAndTasks) { case (user, task) =>
        UserRepository.createOrUpdate(user) *> TaskRepository.create(task)
      }
    } yield ()

  private def createKaitrinTasks(count: Int) =
    for {
      now <- clock.instant
      tasks = List.tabulate(count) { n =>
        NewTask(john.id, TaskText(n.toString), CreatedAt(now.toEpochMilli), kaitrin.id.some)
      }
      _ <- ZIO.foreach_(tasks)(TaskRepository.create)
    } yield ()

  private def confirmTask(cbData: ConfirmTask, from: TgUser) =
    ZIO.accessM[TaskController] {
      _.get
        .routes
        .run(CallbackQueryData(cbData, callbackQuery(cbData, from, inlineMessageId = "0".some)))
        .value
        .map(_.flatten)
    }

  private def callUserRoute(cbData: CbData, from: TgUser) =
    ZIO.accessM[TaskController] {
      _.get
        .userRoutes
        .run(ContextCallbackQuery(john, CallbackQueryData(cbData, callbackQuery(cbData, from, inlineMessageId = "0".some))))
        .value
        .map(_.flatten)
    }

  private object TestEnvironment {
    private val botApi = ZLayer.succeed(botApiMock)
    private val userRepo = UserRepository.live
    private val taskRepo = TaskRepository.live
    private val repositories = userRepo ++ taskRepo
    private val botService = repositories >>> BotService.live
    private val taskController = (ZLayer.requires[Clock] ++ botApi ++ botService ++ repositories) >>> TaskController.live

    val env: URLayer[Clock with Blocking, PostgresITEnv with TaskController with TaskRepository with UserRepository] =
      ZLayer.requires[Clock] ++ TestEnvironments.itLayer >+> taskController ++ repositories
  }
}
