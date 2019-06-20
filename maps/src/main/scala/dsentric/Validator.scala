package dsentric

import scala.util.matching.Regex
import Dsentric._
import PessimisticCodecs._

trait Validator[+T] {

  def apply[S >: T](path:Path, value:Option[S], currentState: => Option[S]): Failures

  def &&[S >: T] (v:Validator[S]):Validator[S] = AndValidator(this, v)

  def ||[S >: T] (v:Validator[S]):Validator[S] = OrValidator(this, v)

  def schemaInfo:DObject = DObject.empty

  private[dsentric] def isInternal:Boolean = false

  private[dsentric] def mask:Option[String] = None

  private[dsentric] def removalDenied:Boolean = false

  private[dsentric] def isEmpty:Boolean = false
}

case class AndValidator[+T, A <: T, B <: T](left:Validator[A], right:Validator[B]) extends Validator[T] {
  def apply[S >: T](path:Path, value:Option[S], currentState: => Option[S]):Failures =
    left(path, value, currentState) ++ right(path, value, currentState)

  private[dsentric] override def isInternal:Boolean = left.isInternal || right.isInternal

  private[dsentric] override def removalDenied:Boolean = left.removalDenied || right.removalDenied

  private[dsentric] override def mask:Option[String] = left.mask.orElse(right.mask)

  override val schemaInfo: DObject = DObject("validationAnd" := DArray(left.schemaInfo, right.schemaInfo))
}

case class OrValidator[+T, A <: T, B <: T](left:Validator[A], right:Validator[B]) extends Validator[T] {
  def apply[S >: T](path:Path, value:Option[S], currentState: => Option[S]):Failures =
    left(path, value, currentState) match {
      case Failures.empty =>
        Failures.empty
      case list =>
        right(path, value, currentState) match {
          case Failures.empty =>
            Failures.empty
          case list2 if list2.size < list.size =>
            list2
          case _ =>
            list
        }
    }

  private[dsentric] override def mask:Option[String] = left.mask.orElse(right.mask)

  private[dsentric] override def removalDenied:Boolean = left.removalDenied || right.removalDenied

  private[dsentric] override def isInternal:Boolean = left.isInternal || right.isInternal

  override val schemaInfo: DObject = DObject("validationOr" := DArray(left.schemaInfo, right.schemaInfo))

}

//TODO separate definition for internal/reserved etc such that the or operator is not supported
trait Validators extends ValidatorOps{

  val empty =
    new Validator[Nothing] {
      def apply[S >: Nothing](path:Path, value: Option[S], currentState: => Option[S]):Failures =
        Failures.empty

      override def isEmpty:Boolean = true
    }

  val internal =
    new Validator[Option[Nothing]] {
      def apply[S >: Option[Nothing]](path:Path, value: Option[S], currentState: => Option[S]): Failures =
        value.fold(Failures.empty)(_ => Failures(path -> "Value is reserved and cannot be provided."))

      private[dsentric] override def isInternal:Boolean = true

      override val schemaInfo: DObject = DObject("internal" := true)

    }

  def mask(masking:String): Validator[Nothing] =
    new Validator[Nothing] {

      def apply[S >: Nothing](path:Path, value: Option[S], currentState: => Option[S]):Failures =
        Failures.empty

      override def mask:Option[String] = Some(masking)

      override val schemaInfo: DObject = DObject("masked" := masking)
    }

  val reserved =
    new Validator[Option[Nothing]] {
      def apply[S >: Option[Nothing]](path: Path, value: Option[S], currentState: => Option[S]): Failures =
        value.fold(Failures.empty)(_ => Failures(path -> "Value is reserved and cannot be provided."))
      override val schemaInfo: DObject = DObject("reserved" := true)
    }

  val immutable =
    new Validator[Nothing] {
      def apply[S >: Nothing](path:Path, value: Option[S], currentState: => Option[S]):Failures =
        (for {
          v <- value
          cs <- currentState
          if v != cs
        } yield
          path -> "Immutable value cannot be changed."
          ).toVector

      override val schemaInfo: DObject = DObject("immutable" := true)
    }




  val increment =
    new Validator[Numeric] {
      def apply[S >: Numeric](path:Path, value: Option[S], currentState: => Option[S]): Failures =
        (for {
          v <- value
          c <- currentState
          r <- resolve(c,v)
          a <- if (r >= 0) Some(path -> "Value must increase.") else None
        } yield a).toVector

      override val schemaInfo: DObject = DObject("valueMustIncrease" := true)
    }

  val decrement =
    new Validator[Numeric] {
      def apply[S >: Numeric](path:Path, value: Option[S], currentState: => Option[S]): Failures =
        (for {
          v <- value
          c <- currentState
          r <- resolve(c,v)
          a <- if (r <= 0) Some(path -> "Value must decrease.") else None
        } yield a).toVector

      override val schemaInfo: DObject = DObject("valueMustDecrease" := true)
    }

  def >(x:Long) = new Validator[Numeric] {
    def apply[S >: Numeric](path: Path, value: Option[S], currentState: => Option[S]): Failures =
      (for {
        v <- value
        c <- compare(x, v)
        if c >= 0
      } yield path -> s"Value $v is not greater than $x.")
      .toVector

    override val schemaInfo: DObject = DObject("greaterThan" := x)
  }

  def >(x:Double) = new Validator[Numeric] {
    def apply[S >: Numeric](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      (for {
        v <- value
        c <- compare(x, v)
        if c >= 0
      } yield path -> s"Value $v is not greater than $x.")
        .toVector

    override val schemaInfo: DObject = DObject("greaterThan" := x)
  }

  def >=(x:Long) = new Validator[Numeric] {
    def apply[S >: Numeric](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      (for {
        v <- value
        c <- compare(x, v)
        if c > 0
      } yield path -> s"Value $v is not greater or equal to $x.")
        .toVector

    override val schemaInfo: DObject = DObject("greaterThanEqual" := x)

  }

  def >=(x:Double) = new Validator[Numeric] {
    def apply[S >: Numeric](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      (for {
        v <- value
        c <- compare(x, v)
        if c > 0
      } yield path -> s"Value $v is not greater or equal to $x.")
        .toVector

    override val schemaInfo: DObject = DObject("greaterThanEqual" := x)

  }

  def <(x:Long) = new Validator[Numeric] {
    def apply[S >: Numeric](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      (for {
        v <- value
        c <- compare(x, v)
        if c <= 0
      } yield path -> s"Value $v is not less than $x.")
        .toVector

    override val schemaInfo: DObject = DObject("lessThan" := x)

  }

  def <(x:Double) = new Validator[Numeric] {
    def apply[S >: Numeric](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      (for {
        v <- value
        c <- compare(x, v)
        if c <= 0
      } yield path -> s"Value $v is not less than $x.")
        .toVector

    override val schemaInfo: DObject = DObject("lessThan" := x)
  }

  def <=(x:Long) = new Validator[Numeric] {
    def apply[S >: Numeric](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      (for {
        v <- value
        c <- compare(x, v)
        if c < 0
      } yield path -> s"Value $v is not less than or equal to $x.")
        .toVector

    override val schemaInfo: DObject = DObject("lessThanEqual" := x)
  }

  def <=(x:Double) = new Validator[Numeric] {
    def apply[S >: Numeric](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      (for {
        v <- value
        c <- compare(x, v)
        if c < 0
      } yield path -> s"Value $v is not less than or equal to $x.")
        .toVector

    override val schemaInfo: DObject = DObject("lessThanEqual" := x)
  }

  def minLength(x: Int) = new Validator[Optionable[Length]] {
    def apply[S >: Optionable[Length]](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      value.flatMap(getLength)
        .filter(_ < x)
        .map(v => path -> s"Value must have a length of at least $x.")
        .toVector

    override val schemaInfo: DObject = DObject("minLength" := x)

  }

  def maxLength(x: Int) = new Validator[Optionable[Length]] {
    def apply[S >: Optionable[Length]](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      value.flatMap(getLength)
        .filter(_ > x)
        .map(v => path -> s"Value must have a length of at most $x.")
        .toVector

    override val schemaInfo: DObject = DObject("maxLength" := x)

  }

  def in[T](values:T*)(implicit codec:DCodec[T]) = new Validator[Optionable[T]] {

    override val schemaInfo: DObject = DObject("symbols" := DArray(values:_*), "caseSensitive" := true)

    def apply[S >: Optionable[T]](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      value
        .flatMap(getT[T, S])
        .filterNot(values.contains)
        .map(v => path -> s"'$v' is not an allowed value.")
        .toVector
  }

  def nin[T](values:T*)(implicit codec:DCodec[T]) = new Validator[Optionable[T]] {

    override val schemaInfo: DObject = DObject("forbidden" := DArray(values:_*), "caseSensitive" := true)

    def apply[S >: Optionable[T]](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      value
        .flatMap(getT[T, S])
        .filter(values.contains)
        .map(v => path -> s"'$v' is not an allowed value.")
        .toVector
  }

  //maybe change to generic equality
  def inCaseInsensitive(values:String*) = new Validator[Optionable[String]] {

    override val schemaInfo: DObject = DObject("symbols" := DArray(values:_*), "caseSensitive" := false)

    def apply[S >: Optionable[String]](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      value
        .flatMap(getString)
        .filterNot(v => values.exists(_.equalsIgnoreCase(v.toString)))
        .map(v => path -> s"'$v' is not an allowed value.")
        .toVector
  }

  def ninCaseInsensitive(values:String*) = new Validator[Optionable[String]] {

    override val schemaInfo: DObject = DObject("forbidden" := DArray(values:_*), "caseSensitive" := false)

    def apply[S >: Optionable[String]](path:Path, value: Option[S], currentState: => Option[S]): Failures =
      value
        .flatMap(getString)
        .filter(v => values.exists(_.equalsIgnoreCase(v.toString)))
        .map(v => path -> s"'$v' is not an allowed value.")
        .toVector
  }

  // TODO: Rewrite as regex?
  val nonEmpty =
    new Validator[Optionable[Length]] {

      val message = "Value must not be empty."
      override val schemaInfo: DObject = DObject("nonEmpty" := true, "message" := message)

      def apply[S >: Optionable[Length]](path:Path, value: Option[S], currentState: => Option[S]): Failures =
        value
          .flatMap(getLength)
          .filter(_ == 0)
          .map(v => path -> message)
          .toVector
    }

  // TODO: Rewrite as regex?
  val nonEmptyOrWhiteSpace =
    new Validator[Optionable[String]] {

      val message = "String must not be empty or whitespace."
      override val schemaInfo: DObject = DObject("nonBlank" := true, "message" := message)

      def apply[S >: Optionable[String]](path: Path, value: Option[S], currentState: => Option[S]): Failures =
        value
          .collect {
            case s: String => s
            case Some(s: String) => s
          }
          .filter(_.trim().isEmpty)
          .map(v => path -> message)
          .toVector
    }

  def custom[T](f: T => Boolean, message:String) =
    new Validator[Optionable[T]] {

      override val schemaInfo: DObject = DObject("message" := message)

      def apply[S >: Optionable[T]](path: Path, value: Option[S], currentState: => Option[S]): Failures =
        value
          .flatMap(getT[T, S])
          .toVector.flatMap{ s =>
          if (f(s)) Vector.empty
          else Vector(path -> message)
        }
    }

  def regex(r:Regex):Validator[Optionable[String]] =
    regex(r, s"String fails to match pattern '$r'.")

  def regex(r:Regex, message:String):Validator[Optionable[String]] =
    new Validator[Optionable[String]] {

      override val schemaInfo: DObject = DObject("regex" := r.pattern.pattern(), "message" := message)

      def apply[S >: Optionable[String]](path:Path, value: Option[S], currentState: => Option[S]): Failures =
        value
          .flatMap(getString)
          .toVector
          .flatMap{ s =>
            if (r.pattern.matcher(s).matches) Vector.empty
            else Vector(path -> message)
          }
    }



  def noKeyRemoval:Validator[Optionable[Map[String,Nothing]]] =
    new Validator[Optionable[Map[String, Nothing]]] {
      def apply[S >: Optionable[Map[String, Nothing]]](path: Path, value: Option[S], currentState: => Option[S]): Failures = {
        Vector.empty
      }

      private[dsentric] override def removalDenied:Boolean = true
    }


  def mapContract[D <: DObject](contract:ContractFor[D]): Validator[Optionable[Map[String, D]]] =
    mapContractK[String, D](contract)

  def mapContractK[K, D <: DObject](contract:ContractFor[D]): Validator[Optionable[Map[K, D]]] =
    new Validator[Optionable[Map[K,D]]] {

      def apply[S >: Optionable[Map[K, D]]](path: Path, value: Option[S], currentState: => Option[S]): Failures = {
        val c =
          for {
            co <- value
            ct <- getT[Map[K, D], S](co)
          } yield ct

        val failures =
          for {
            o <- value.toIterator
            t <- getT[Map[K, D], S](o).toIterator
            kv <- t.toIterator
            f <- contract._validateFields(path \ kv._1.toString, kv._2.value, c.flatMap(_.get(kv._1).map(_.value)))
          } yield f

        failures.toVector
      }

    }

  def keyValidator(r:Regex, message:String):Validator[Optionable[DObject]] =
    new Validator[Optionable[DObject]] {

      override val schemaInfo: DObject = DObject("message" := message, "keys" := DObject("regex" := r.pattern.pattern()))

      def apply[S >: Optionable[DObject]](path:Path, value: Option[S], currentState: => Option[S]): Failures =
        for {
          co <- value.toVector
          ct <- getT[DObject, S](co).toVector
          key <- ct.keys.toVector if !r.pattern.matcher(key).matches()
        } yield path \ key -> message
    }

  def keyValidator[T](message:String)(implicit D:DCodec[T]):Validator[Optionable[DObject]] =
    new Validator[Optionable[DObject]] {

      override val schemaInfo: DObject = DObject("message" := message, "keys" := DObject("type" := D.schemaName))

      def apply[S >: Optionable[DObject]](path:Path, value: Option[S], currentState: => Option[S]): Failures =
        for {
          co <- value.toVector
          ct <- getT[DObject, S](co).toVector
          key <- ct.keys.toVector if D.unapply(key).isEmpty
        } yield path \ key -> message
    }
}

trait ValidatorOps {

  protected def getLength[S >: Optionable[Length]](x:S) =
    x match {
      case s:Seq[Any] @unchecked =>
        Some(s.size)
      case a:Iterable[_] =>
        Some(a.size)
      case s:String =>
        Some(s.size)
      case Some(s:Seq[Any] @unchecked) =>
        Some(s.size)
      case Some(a:Iterable[_]) =>
        Some(a.size)
      case Some(s:String) =>
        Some(s.length)
      case _ =>
        None
    }

  protected def getString[S >: Optionable[String]](x:S):Option[String] =
    x match {
      case Some(s:String) => Some(s)
      case s:String => Some(s)
      case _ =>  None
    }

  protected def getT[T, S >: Optionable[T]](t:S):Option[T] =
    t match {
      case Some(s: T@unchecked) => Some(s)
      case None => None
      case s: T@unchecked => Some(s)
    }

  protected def resolve[S >: Numeric](value:S, target:S):Option[Int] =
    value match {
      case i:Int =>
        compare(i, target)
      case l:Long =>
        compare(l, target)
      case f:Float =>
        compare(f, target)
      case d:Double =>
        compare(d, target)
      case Some(i:Int) =>
        compare(i, target)
      case Some(l:Long) =>
        compare(l, target)
      case Some(f:Float) =>
        compare(f, target)
      case Some(d:Double) =>
        compare(d, target)
      case _ =>
        None
    }

  protected def compare[S >: Numeric](value:Long, target:S):Option[Int] =
    target match {
      case i:Int =>
        Some(value.compare(i))
      case l:Long =>
        Some(value.compare(l))
      case f:Float =>
        Some(value.toDouble.compare(f))
      case d:Double =>
        Some(value.toDouble.compare(d))
      case Some(i:Int) =>
        Some(value.compare(i))
      case Some(l:Long) =>
        Some(value.compare(l))
      case Some(f:Float) =>
        Some(value.toDouble.compare(f))
      case Some(d:Double) =>
        Some(value.toDouble.compare(d))
      case _ =>
        None
    }

  protected def compare[S >: Numeric](value:Double, target:S):Option[Int] =
    target match {
      case i:Int =>
        Some(value.compare(i))
      case l:Long =>
        Some(value.compare(l))
      case f:Float =>
        Some(value.compare(f))
      case d:Double =>
        Some(value.compare(d))
      case Some(i:Int) =>
        Some(value.compare(i))
      case Some(l:Long) =>
        Some(value.compare(l))
      case Some(f:Float) =>
        Some(value.compare(f))
      case Some(d:Double) =>
        Some(value.compare(d))
      case _ =>
        None
    }
}

object Validators extends Validators

object ValidationText {

  val UNEXPECTED_TYPE = "Value is not of the expected type."
  val EXPECTED_VALUE = "Value was expected."

}