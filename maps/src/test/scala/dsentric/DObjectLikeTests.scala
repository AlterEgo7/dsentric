package dsentric

import java.util.UUID

import cats.data.NonEmptyList
import dsentric._
import org.scalatest.{FunSuite, Matchers}

case class Custom(value:Map[String, Any]) extends DObject with DObjectLike[Custom] {
  protected def wrap(value: Map[String, Any]): Custom =
    Custom(value)
}

case class CustomParams(id:UUID)(val value:Map[String, Any]) extends DObject with DObjectLike[CustomParams] {
  protected def wrap(value: Map[String, Any]): CustomParams =
    CustomParams(id)(value)
}

class DObjectLikeTests extends FunSuite with Matchers {

  import Dsentric._
  import PessimisticCodecs._

  import Validators._

  object CustomContract extends ContractFor[Custom] {
    val string = \[String]
    val nested = new \\ {
      val value = \[Int]
    }
  }

  object CustomParamsContract extends ContractFor[CustomParams] {
    val string = \[String]
  }

  test("Custom crud return custom") {
    val custom = Custom(Map("nested" -> Map("value" -> 4)))
    custom match {
      case CustomContract.nested.value(4) =>
        assert(true)
      case _ =>
        assert(false)
    }
    val newCustom:Custom =
      CustomContract.nested.value.$set(4)(Custom(Map.empty))

    newCustom shouldBe custom

    val dropCustom =
      CustomContract.string.$set("Value") ~
      CustomContract.nested.$forceDrop
  }
  implicit val customCodec =
    DefaultCodecs.dObjectLikeCodec[Custom](Custom)

  object WithCustomContract extends Contract {
    val custom = \[Custom]
    val mapOfCustom = \[Map[String, Custom]](mapContract(CustomContract))
  }

  test("Nested custom object") {
    val custom = Custom(Map("string" -> "STRING"))
    val dObject = WithCustomContract.custom.$set(custom)(DObject.empty)

    dObject match {
      case WithCustomContract.custom(c) =>
        c shouldBe custom
    }
  }

  test("Validation of custom contract") {
    val custom = Custom(Map("string" -> "STRING"))
    val dObject = (WithCustomContract.custom.$set(custom) ~ WithCustomContract.mapOfCustom.$set(Map("first" -> custom)))(DObject.empty)
    WithCustomContract.$validate(dObject) shouldBe Left(NonEmptyList((Path("mapOfCustom", "first", "nested"),"Value was expected."), Nil))
  }

  test("Extracting with Custom Params") {
    val customParams = CustomParams(new UUID(123,456))(Map("string" -> "String"))

    customParams match {
      case CustomParams(id) && CustomParamsContract.string(s) =>
        id shouldBe new UUID(123,456)
        s shouldBe "String"
      case _ =>
        assert(false)
    }
  }

  test("Iterator functionality") {
    val data = DObject("one" := 1, "two" := "string")
    val v = data.toVector
    v shouldBe Vector("one" := 1, "two" := "string")
  }

  test("Diff a delta") {
    val original = DObject("key1" := 1, "key2" := DObject("key3" := 4, "key5" := 5, "key6" := 6), "key7" := 7, "key8" := 8)
    val delta = DObject("key2" := DObject("key3" := 4, "key5" := DNull, "key6" := 10), "key7" := 7, "key8" := 9)

    original.diff(delta) shouldBe DObject("key2" := DObject("key5" := DNull, "key6" := 10),  "key8" := 9)
  }

}
