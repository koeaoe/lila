package lila.video

import org.joda.time.DateTime
import reactivemongo.bson._
import reactivemongo.core.commands._

import lila.db.Types.Coll
import lila.user.{ User, UserRepo }

private[video] final class VideoApi(
    videoColl: Coll,
    viewColl: Coll) {

  import lila.db.BSON.BSONJodaDateTimeHandler
  import reactivemongo.bson.Macros
  private implicit val VideoBSONHandler = Macros.handler[Video]
  import View.viewBSONHandler

  object video {

    def find(id: Video.ID): Fu[Option[Video]] =
      videoColl.find(BSONDocument("_id" -> id)).one[Video]

    def save(video: Video): Funit =
      videoColl.update(
        BSONDocument("_id" -> video.id),
        video,
        upsert = true).void

    def removeNotIn(ids: List[Video.ID]) =
      videoColl.remove(
        BSONDocument("_id" -> BSONDocument("$nin" -> ids))
      ).void
  }

  object view {

    def find(videoId: Video.ID, userId: String): Fu[Option[View]] =
      viewColl.find(BSONDocument(
        View.BSONFields.id -> View.makeId(videoId, userId)
      )).one[View]

    def add(a: View) = viewColl insert a void

    def hasSeen(user: User, video: Video): Fu[Boolean] =
      viewColl.db command Count(viewColl.name, BSONDocument(
        View.BSONFields.id -> View.makeId(video.id, user.id)
      ).some) map (0!=)
  }
}