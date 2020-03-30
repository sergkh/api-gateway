import akka.util.ByteString
import models.ConfirmationCode
import org.scalatestplus.play.PlaySpec
import utils.KryoSerializer._

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 29/05/17
  */
class KryoSpec extends PlaySpec {

  "Kryo" must {
    "serialize/deserialize ConfirmationCode" in {
      val entity = ConfirmationCode("user@gmail.com", "registration", "123456", 6, Some((
        Seq("Content-Type" -> "application/json"),
        Some(ByteString("""{"login":"user"}""")))
      ))

      val bytes = toBytes[ConfirmationCode](entity)
      val kryoEntity = fromBytes[ConfirmationCode](bytes)

      kryoEntity.login mustEqual entity.login
      kryoEntity.operation mustBe entity.operation
      kryoEntity.codeHash mustBe entity.codeHash //empty
      kryoEntity.request mustBe defined
      kryoEntity.request mustBe entity.request

      val entity2 = ConfirmationCode("user@gmail.com", "registration", "123456", 6, None)

      fromBytes[ConfirmationCode](toBytes[ConfirmationCode](entity2)).request mustBe empty
    }
  }

}
