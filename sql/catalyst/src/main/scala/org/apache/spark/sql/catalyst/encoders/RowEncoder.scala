/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.encoders

import scala.collection.Map
import scala.reflect.ClassTag

import org.apache.spark.SparkException
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.util.{ArrayBasedMapData, DateTimeUtils, GenericArrayData}
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

/**
 * A factory for constructing encoders that convert external row to/from the Spark SQL
 * internal binary representation.
 */
object RowEncoder {
  def apply(schema: StructType): ExpressionEncoder[Row] = {
    val cls = classOf[Row]
    val inputObject = BoundReference(0, ObjectType(cls), nullable = true)
    // We use an If expression to wrap extractorsFor result of StructType
    val serializer = serializerFor(inputObject, schema).asInstanceOf[If].falseValue
    val deserializer = deserializerFor(schema)
    new ExpressionEncoder[Row](
      schema,
      flat = false,
      serializer.asInstanceOf[CreateStruct].children,
      deserializer,
      ClassTag(cls))
  }

  private def serializerFor(
      inputObject: Expression,
      inputType: DataType): Expression = inputType match {
    case NullType | BooleanType | ByteType | ShortType | IntegerType | LongType |
         FloatType | DoubleType | BinaryType | CalendarIntervalType => inputObject

    case p: PythonUserDefinedType => serializerFor(inputObject, p.sqlType)

    case udt: UserDefinedType[_] =>
      val annotation = udt.userClass.getAnnotation(classOf[SQLUserDefinedType])
      val udtClass: Class[_] = if (annotation != null) {
        annotation.udt()
      } else {
        UDTRegistration.getUDTFor(udt.userClass.getName).getOrElse {
          throw new SparkException(s"${udt.userClass.getName} is not annotated with " +
            "SQLUserDefinedType nor registered with UDTRegistration.}")
        }
      }
      val obj = NewInstance(
        udtClass,
        Nil,
        dataType = ObjectType(udtClass), false)
      Invoke(obj, "serialize", udt.sqlType, inputObject :: Nil)

    case TimestampType =>
      StaticInvoke(
        DateTimeUtils.getClass,
        TimestampType,
        "fromJavaTimestamp",
        inputObject :: Nil)

    case DateType =>
      StaticInvoke(
        DateTimeUtils.getClass,
        DateType,
        "fromJavaDate",
        inputObject :: Nil)

    case _: DecimalType =>
      StaticInvoke(
        Decimal.getClass,
        DecimalType.SYSTEM_DEFAULT,
        "fromDecimal",
        inputObject :: Nil)

    case StringType =>
      StaticInvoke(
        classOf[UTF8String],
        StringType,
        "fromString",
        inputObject :: Nil)

    case t @ ArrayType(et, _) => et match {
      case BooleanType | ByteType | ShortType | IntegerType | LongType | FloatType | DoubleType =>
        NewInstance(
          classOf[GenericArrayData],
          inputObject :: Nil,
          dataType = t)
      case _ => MapObjects(serializerFor(_, et), inputObject, externalDataTypeForInput(et))
    }

    case t @ MapType(kt, vt, valueNullable) =>
      val keys =
        Invoke(
          Invoke(inputObject, "keysIterator", ObjectType(classOf[scala.collection.Iterator[_]])),
          "toSeq",
          ObjectType(classOf[scala.collection.Seq[_]]))
      val convertedKeys = serializerFor(keys, ArrayType(kt, false))

      val values =
        Invoke(
          Invoke(inputObject, "valuesIterator", ObjectType(classOf[scala.collection.Iterator[_]])),
          "toSeq",
          ObjectType(classOf[scala.collection.Seq[_]]))
      val convertedValues = serializerFor(values, ArrayType(vt, valueNullable))

      NewInstance(
        classOf[ArrayBasedMapData],
        convertedKeys :: convertedValues :: Nil,
        dataType = t)

    case StructType(fields) =>
      val convertedFields = fields.zipWithIndex.map { case (f, i) =>
        val method = if (f.dataType.isInstanceOf[StructType]) {
          "getStruct"
        } else {
          "get"
        }
        If(
          Invoke(inputObject, "isNullAt", BooleanType, Literal(i) :: Nil),
          Literal.create(null, f.dataType),
          serializerFor(
            Invoke(inputObject, method, externalDataTypeForInput(f.dataType), Literal(i) :: Nil),
            f.dataType))
      }
      If(IsNull(inputObject),
        Literal.create(null, inputType),
        CreateStruct(convertedFields))
  }

  /**
   * Returns the `DataType` that can be used when generating code that converts input data
   * into the Spark SQL internal format.  Unlike `externalDataTypeFor`, the `DataType` returned
   * by this function can be more permissive since multiple external types may map to a single
   * internal type.  For example, for an input with DecimalType in external row, its external types
   * can be `scala.math.BigDecimal`, `java.math.BigDecimal`, or
   * `org.apache.spark.sql.types.Decimal`.
   */
  private def externalDataTypeForInput(dt: DataType): DataType = dt match {
    // In order to support both Decimal and java BigDecimal in external row, we make this
    // as java.lang.Object.
    case _: DecimalType => ObjectType(classOf[java.lang.Object])
    case _ => externalDataTypeFor(dt)
  }

  private def externalDataTypeFor(dt: DataType): DataType = dt match {
    case _ if ScalaReflection.isNativeType(dt) => dt
    case CalendarIntervalType => dt
    case TimestampType => ObjectType(classOf[java.sql.Timestamp])
    case DateType => ObjectType(classOf[java.sql.Date])
    case _: DecimalType => ObjectType(classOf[java.math.BigDecimal])
    case StringType => ObjectType(classOf[java.lang.String])
    case _: ArrayType => ObjectType(classOf[scala.collection.Seq[_]])
    case _: MapType => ObjectType(classOf[scala.collection.Map[_, _]])
    case _: StructType => ObjectType(classOf[Row])
    case udt: UserDefinedType[_] => ObjectType(udt.userClass)
    case _: NullType => ObjectType(classOf[java.lang.Object])
  }

  private def deserializerFor(schema: StructType): Expression = {
    val fields = schema.zipWithIndex.map { case (f, i) =>
      val dt = f.dataType match {
        case p: PythonUserDefinedType => p.sqlType
        case other => other
      }
      val field = BoundReference(i, dt, f.nullable)
      If(
        IsNull(field),
        Literal.create(null, externalDataTypeFor(dt)),
        deserializerFor(field)
      )
    }
    CreateExternalRow(fields, schema)
  }

  private def deserializerFor(input: Expression): Expression = input.dataType match {
    case NullType | BooleanType | ByteType | ShortType | IntegerType | LongType |
         FloatType | DoubleType | BinaryType | CalendarIntervalType => input

    case udt: UserDefinedType[_] =>
      val annotation = udt.userClass.getAnnotation(classOf[SQLUserDefinedType])
      val udtClass: Class[_] = if (annotation != null) {
        annotation.udt()
      } else {
        UDTRegistration.getUDTFor(udt.userClass.getName).getOrElse {
          throw new SparkException(s"${udt.userClass.getName} is not annotated with " +
            "SQLUserDefinedType nor registered with UDTRegistration.}")
        }
      }
      val obj = NewInstance(
        udtClass,
        Nil,
        dataType = ObjectType(udtClass))
      Invoke(obj, "deserialize", ObjectType(udt.userClass), input :: Nil)

    case TimestampType =>
      StaticInvoke(
        DateTimeUtils.getClass,
        ObjectType(classOf[java.sql.Timestamp]),
        "toJavaTimestamp",
        input :: Nil)

    case DateType =>
      StaticInvoke(
        DateTimeUtils.getClass,
        ObjectType(classOf[java.sql.Date]),
        "toJavaDate",
        input :: Nil)

    case _: DecimalType =>
      Invoke(input, "toJavaBigDecimal", ObjectType(classOf[java.math.BigDecimal]))

    case StringType =>
      Invoke(input, "toString", ObjectType(classOf[String]))

    case ArrayType(et, nullable) =>
      val arrayData =
        Invoke(
          MapObjects(deserializerFor(_), input, et),
          "array",
          ObjectType(classOf[Array[_]]))
      StaticInvoke(
        scala.collection.mutable.WrappedArray.getClass,
        ObjectType(classOf[Seq[_]]),
        "make",
        arrayData :: Nil)

    case MapType(kt, vt, valueNullable) =>
      val keyArrayType = ArrayType(kt, false)
      val keyData = deserializerFor(Invoke(input, "keyArray", keyArrayType))

      val valueArrayType = ArrayType(vt, valueNullable)
      val valueData = deserializerFor(Invoke(input, "valueArray", valueArrayType))

      StaticInvoke(
        ArrayBasedMapData.getClass,
        ObjectType(classOf[Map[_, _]]),
        "toScalaMap",
        keyData :: valueData :: Nil)

    case schema @ StructType(fields) =>
      val convertedFields = fields.zipWithIndex.map { case (f, i) =>
        If(
          Invoke(input, "isNullAt", BooleanType, Literal(i) :: Nil),
          Literal.create(null, externalDataTypeFor(f.dataType)),
          deserializerFor(GetStructField(input, i)))
      }
      If(IsNull(input),
        Literal.create(null, externalDataTypeFor(input.dataType)),
        CreateExternalRow(convertedFields, schema))
  }
}
