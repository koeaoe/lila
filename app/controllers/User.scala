package controllers

import play.api.mvc._, Results._

import lila.api.Context
import lila.app._
import lila.app.mashup.GameFilterMenu
import lila.common.LilaCookie
import lila.db.api.$find
import lila.game.{ GameRepo, Pov }
import lila.rating.PerfType
import lila.security.Permission
import lila.user.tube.userTube
import lila.user.{ User => UserModel, UserRepo }
import lila.evaluation.{PlayerAggregateAssessment}
import views._

object User extends LilaController {

  private def env = Env.user
  private def gamePaginator = Env.game.paginator
  private def forms = lila.user.DataForm
  private def relationApi = Env.relation.api

  def tv(username: String) = Open { implicit ctx =>
    OptionFuResult(UserRepo named username) { user =>
      (GameRepo lastPlayedPlaying user) orElse
        (GameRepo lastPlayed user) flatMap {
          _.fold(fuccess(Redirect(routes.User.show(username)))) { pov =>
            Round.watch(pov, userTv = user.some)
          }
        }
    }
  }

  def show(username: String) = Open { implicit ctx =>
    filter(username, none, 1)
  }

  def showMini(username: String) = Open { implicit ctx =>
    OptionFuResult(UserRepo named username) { user =>
      GameRepo lastPlayedPlaying user zip
        (ctx.userId ?? { relationApi.blocks(user.id, _) }) zip
        (ctx.isAuth ?? { Env.pref.api.followable(user.id) }) zip
        (ctx.userId ?? { relationApi.relation(_, user.id) }) map {
          case (((pov, blocked), followable), relation) =>
            Ok(html.user.mini(user, pov, blocked, followable, relation))
              .withHeaders(CACHE_CONTROL -> "max-age=5")
        }
    }
  }

  def showFilter(username: String, filterName: String, page: Int) = Open { implicit ctx =>
    filter(username, filterName.some, page)
  }

  def online = Open { implicit req =>
    val max = 1000
    UserRepo.byIdsSortRating(env.onlineUserIdMemo.keys, max) map { html.user.online(_, max) }
  }

  private def filter(
    username: String,
    filterOption: Option[String],
    page: Int,
    status: Results.Status = Results.Ok)(implicit ctx: Context) =
    Reasonable(page) {
      OptionFuResult(UserRepo named username) { u =>
        (u.enabled || isGranted(_.UserSpy)).fold({
          if (lila.common.HTTPRequest.isSynchronousHttp(ctx.req))
            userShow(u, filterOption, page)
          else
            userGames(u, filterOption, page)
        } map { status(_) },
          fuccess(NotFound(html.user.disabled(u)))
        )
      }
    }

  private def userShow(u: UserModel, filterOption: Option[String], page: Int)(implicit ctx: Context) = for {
    info ← Env.current.userInfo(u, ctx)
    filters = GameFilterMenu(info, ctx.me, filterOption)
    pag <- GameFilterMenu.paginatorOf(
      user = u,
      info = info.some,
      filter = filters.current,
      me = ctx.me,
      page = page)
    relation <- ctx.userId ?? { relationApi.relation(_, u.id) }
    notes <- ctx.me ?? { me =>
      relationApi friends me.id flatMap { env.noteApi.get(u, me, _) }
    }
    followable <- ctx.isAuth ?? { Env.pref.api followable u.id }
    blocked <- ctx.userId ?? { relationApi.blocks(u.id, _) }
  } yield html.user.show(u, info, pag, filters, relation, notes, followable, blocked)

  private def userGames(u: UserModel, filterOption: Option[String], page: Int)(implicit ctx: Context) = {
    import lila.app.mashup.GameFilter.{ All, Playing }
    filterOption.fold({
      Env.simul isHosting u.id map (_.fold(Playing, All).name)
    })(fuccess) flatMap { filterName =>
      GameFilterMenu.paginatorOf(
        user = u,
        info = none,
        filter = GameFilterMenu.currentOf(GameFilterMenu.all, filterName),
        me = ctx.me,
        page = page
      ) map { html.user.games(u, _, filterName) }
    }
  }

  def list = Open { implicit ctx =>
    val nb = 10
    for {
      bullet ← env.cached topPerf PerfType.Bullet.key
      blitz ← env.cached topPerf PerfType.Blitz.key
      classical ← env.cached topPerf PerfType.Classical.key
      correspondence ← env.cached topPerf PerfType.Correspondence.key
      chess960 ← env.cached topPerf PerfType.Chess960.key
      kingOfTheHill ← env.cached topPerf PerfType.KingOfTheHill.key
      threeCheck ← env.cached topPerf PerfType.ThreeCheck.key
      antichess <- env.cached topPerf PerfType.Antichess.key
      atomic <- env.cached topPerf PerfType.Atomic.key
      horde <- env.cached topPerf PerfType.Horde.key
      nbAllTime ← env.cached topNbGame nb map2 { (user: UserModel) =>
        user -> user.count.game
      }
      nbWeek ← Env.game.cached activePlayerUidsWeek nb flatMap { pairs =>
        UserRepo.byOrderedIds(pairs.map(_.userId)) map (_ zip pairs.map(_.nb))
      }
      tourneyWinners ← Env.tournament.winners scheduled nb
      online ← env.cached topOnline 40
    } yield html.user.list(
      tourneyWinners = tourneyWinners,
      online = online,
      bullet = bullet,
      blitz = blitz,
      classical = classical,
      correspondence = correspondence,
      chess960 = chess960,
      kingOfTheHill = kingOfTheHill,
      threeCheck = threeCheck,
      antichess = antichess,
      atomic = atomic,
      horde = horde,
      nbWeek = nbWeek,
      nbAllTime = nbAllTime)
  }

  def mod(username: String) = Secure(_.UserSpy) { implicit ctx =>
    me => OptionFuOk(UserRepo named username) { user =>
      (Env.security userSpy user.id) zip (Env.mod.assessApi.getPlayerAggregateAssessment(user.id)) map {
        case (spy, playerAggregateAssessment) => html.user.mod(user, spy, playerAggregateAssessment)
      }
    }
  }

  def writeNote(username: String) = AuthBody { implicit ctx =>
    me => OptionFuResult(UserRepo named username) { user =>
      implicit val req = ctx.body
      env.forms.note.bindFromRequest.fold(
        err => filter(username, none, 1, Results.BadRequest),
        text => env.noteApi.write(user, text, me) inject Redirect(routes.User.show(username).url + "?note")
      )
    }
  }

  def opponents(username: String) = Open { implicit ctx =>
    OptionFuOk(UserRepo named username) { user =>
      lila.game.BestOpponents(user.id, 50) flatMap { ops =>
        ctx.isAuth.fold(
          Env.pref.api.followables(ops map (_._1.id)),
          fuccess(List.fill(50)(true))
        ) flatMap { followables =>
            (ops zip followables).map {
              case ((u, nb), followable) => ctx.userId ?? { myId =>
                relationApi.relation(myId, u.id)
              } map { lila.relation.Related(u, nb, followable, _) }
            }.sequenceFu map { relateds =>
              html.user.opponents(user, relateds)
            }
          }
      }
    }
  }

  def autocomplete = Open { implicit ctx =>
    get("term", ctx.req).filter(_.nonEmpty).fold(BadRequest("No search term provided").fuccess: Fu[Result]) { term =>
      JsonOk(UserRepo usernamesLike term)
    }
  }
}
