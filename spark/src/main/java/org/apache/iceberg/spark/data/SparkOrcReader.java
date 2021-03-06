/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.spark.data;

import org.apache.iceberg.Schema;
import org.apache.iceberg.orc.ColumnIdMap;
import org.apache.iceberg.orc.OrcValueReader;
import org.apache.iceberg.orc.TypeConversion;
import org.apache.orc.TypeDescription;
import org.apache.orc.storage.ql.exec.vector.BytesColumnVector;
import org.apache.orc.storage.ql.exec.vector.ColumnVector;
import org.apache.orc.storage.ql.exec.vector.DecimalColumnVector;
import org.apache.orc.storage.ql.exec.vector.DoubleColumnVector;
import org.apache.orc.storage.ql.exec.vector.ListColumnVector;
import org.apache.orc.storage.ql.exec.vector.LongColumnVector;
import org.apache.orc.storage.ql.exec.vector.MapColumnVector;
import org.apache.orc.storage.ql.exec.vector.StructColumnVector;
import org.apache.orc.storage.ql.exec.vector.TimestampColumnVector;
import org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch;
import org.apache.orc.storage.serde2.io.DateWritable;
import org.apache.orc.storage.serde2.io.HiveDecimalWritable;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.SpecializedGetters;
import org.apache.spark.sql.catalyst.expressions.codegen.UnsafeArrayWriter;
import org.apache.spark.sql.catalyst.expressions.codegen.UnsafeRowWriter;
import org.apache.spark.sql.catalyst.expressions.codegen.UnsafeWriter;
import org.apache.spark.sql.catalyst.util.ArrayData;
import org.apache.spark.sql.catalyst.util.MapData;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.unsafe.Platform;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * Converts the OrcInterator, which returns ORC's VectorizedRowBatch to a
 * set of Spark's UnsafeRows.
 *
 * It minimizes allocations by reusing most of the objects in the implementation.
 */
public class SparkOrcReader implements OrcValueReader<InternalRow> {
  private final static int INITIAL_SIZE = 128 * 1024;
  private final int numFields;
  private final TypeDescription readSchema;

  public SparkOrcReader(Schema readSchema) {
    this.readSchema = TypeConversion.toOrc(readSchema, new ColumnIdMap());
    numFields = readSchema.columns().size();
  }

  private Converter[] buildConverters(final UnsafeRowWriter writer) {
    final Converter[] converters = new Converter[numFields];
    for(int c = 0; c < numFields; ++c) {
      converters[c] = buildConverter(writer, readSchema.getChildren().get(c));
    }
    return converters;
  }

  @Override
  public InternalRow read(VectorizedRowBatch batch, int row) {
    final UnsafeRowWriter rowWriter = new UnsafeRowWriter(numFields, INITIAL_SIZE);
    final Converter[] converters = buildConverters(rowWriter);

    rowWriter.reset();
    rowWriter.zeroOutNullBytes();
    for(int c=0; c < batch.cols.length; ++c) {
      converters[c].convert(rowWriter, c, batch.cols[c], row);
    }
    return rowWriter.getRow();
  }

  private static String rowToString(SpecializedGetters row, TypeDescription schema) {
    final List<TypeDescription> children = schema.getChildren();
    final StringBuilder rowBuilder = new StringBuilder("{");

    for(int c = 0; c < children.size(); ++c) {
      rowBuilder.append("\"");
      rowBuilder.append(schema.getFieldNames().get(c));
      rowBuilder.append("\": ");
      rowBuilder.append(rowEntryToString(row, c, children.get(c)));
      if (c != children.size() - 1) {
        rowBuilder.append(", ");
      }
    }
    rowBuilder.append("}");
    return rowBuilder.toString();
  }

  private static String rowEntryToString(SpecializedGetters row, int ord, TypeDescription schema) {
    switch (schema.getCategory()) {
      case BOOLEAN:
        return Boolean.toString(row.getBoolean(ord));
      case BYTE:
        return Byte.toString(row.getByte(ord));
      case SHORT:
        return Short.toString(row.getShort(ord));
      case INT:
        return Integer.toString(row.getInt(ord));
      case LONG:
        return Long.toString(row.getLong(ord));
      case FLOAT:
        return Float.toString(row.getFloat(ord));
      case DOUBLE:
        return Double.toString(row.getDouble(ord));
      case CHAR:
      case VARCHAR:
      case STRING:
        return "\"" + row.getUTF8String(ord) + "\"";
      case BINARY: {
        byte[] bin = row.getBinary(ord);
        final StringBuilder binStr;
        if (bin == null) {
          binStr = new StringBuilder("null");
        } else {
          binStr = new StringBuilder("[");
          for (int i = 0; i < bin.length; ++i) {
            if (i != 0) {
              binStr.append(", ");
            }
            int v = bin[i] & 0xff;
            if (v < 16) {
              binStr.append("0");
              binStr.append(Integer.toHexString(v));
            } else {
              binStr.append(Integer.toHexString(v));
            }
          }
          binStr.append("]");
        }
        return binStr.toString();
      }
      case DECIMAL:
        return row.getDecimal(ord, schema.getPrecision(), schema.getScale()).toString();
      case DATE:
        return "\"" + new DateWritable(row.getInt(ord)) + "\"";
      case TIMESTAMP:
        return "\"" + new Timestamp(row.getLong(ord)) + "\"";
      case STRUCT:
        return rowToString(row.getStruct(ord, schema.getChildren().size()), schema);
      case LIST: {
        TypeDescription child = schema.getChildren().get(0);
        final StringBuilder listStr = new StringBuilder("[");
        ArrayData list = row.getArray(ord);
        for(int e=0; e < list.numElements(); ++e) {
          if (e != 0) {
            listStr.append(", ");
          }
          listStr.append(rowEntryToString(list, e, child));
        }
        listStr.append("]");
        return listStr.toString();
      }
      case MAP: {
        TypeDescription keyType = schema.getChildren().get(0);
        TypeDescription valueType = schema.getChildren().get(1);
        MapData map = row.getMap(ord);
        ArrayData keys = map.keyArray();
        ArrayData values = map.valueArray();
        StringBuilder mapStr = new StringBuilder("[");
        for(int e=0; e < map.numElements(); ++e) {
          if (e != 0) {
            mapStr.append(", ");
          }
          mapStr.append(rowEntryToString(keys, e, keyType));
          mapStr.append(": ");
          mapStr.append(rowEntryToString(values, e, valueType));
        }
        mapStr.append("]");
        return mapStr.toString();
      }
      default:
        throw new IllegalArgumentException("Unhandled type " + schema);
    }
  }

  private static int getArrayElementSize(TypeDescription type) {
    switch (type.getCategory()) {
      case BOOLEAN:
      case BYTE:
        return 1;
      case SHORT:
        return 2;
      case INT:
      case FLOAT:
        return 4;
      default:
        return 8;
    }
  }

  /**
   * The common interface for converting from a ORC ColumnVector to a Spark
   * UnsafeRow. UnsafeRows need two different interfaces for writers and thus
   * we have two methods the first is for structs (UnsafeRowWriter) and the
   * second is for lists and maps (UnsafeArrayWriter). If Spark adds a common
   * interface similar to SpecializedGetters we could that and a single set of
   * methods.
   */
  interface Converter {
    void convert(UnsafeRowWriter writer, int column, ColumnVector vector, int row);
    void convert(UnsafeArrayWriter writer, int element, ColumnVector vector, int row);
  }

  private static class BooleanConverter implements Converter {
    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        writer.write(column, ((LongColumnVector) vector).vector[row] != 0);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        writer.write(element, ((LongColumnVector) vector).vector[row] != 0);
      }
    }
  }

  private static class ByteConverter implements Converter {
    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector,
                        int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        writer.write(column, (byte) ((LongColumnVector) vector).vector[row]);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        writer.write(element, (byte) ((LongColumnVector) vector).vector[row]);
      }
    }
  }

  private static class ShortConverter implements Converter {
    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector,
                        int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        writer.write(column, (short) ((LongColumnVector) vector).vector[row]);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        writer.write(element, (short) ((LongColumnVector) vector).vector[row]);
      }
    }
  }

  private static class IntConverter implements Converter {
    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector,
                        int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        writer.write(column, (int) ((LongColumnVector) vector).vector[row]);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        writer.write(element, (int) ((LongColumnVector) vector).vector[row]);
      }
    }
  }

  private static class LongConverter implements Converter {
    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector,
                        int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        writer.write(column, ((LongColumnVector) vector).vector[row]);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        writer.write(element, ((LongColumnVector) vector).vector[row]);
      }
    }
  }

  private static class FloatConverter implements Converter {
    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector,
                        int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        writer.write(column, (float) ((DoubleColumnVector) vector).vector[row]);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        writer.write(element, (float) ((DoubleColumnVector) vector).vector[row]);
      }
    }
  }

  private static class DoubleConverter implements Converter {
    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector,
                        int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        writer.write(column, ((DoubleColumnVector) vector).vector[row]);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        writer.write(element, ((DoubleColumnVector) vector).vector[row]);
      }
    }
  }

  private static class TimestampConverter implements Converter {

    private long convert(TimestampColumnVector vector, int row) {
      // compute microseconds past 1970.
      return (vector.time[row]/1000) * 1_000_000 + vector.nanos[row] / 1000;
    }

    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector,
                        int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        writer.write(column, convert((TimestampColumnVector) vector, row));
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        writer.write(element, convert((TimestampColumnVector) vector, row));
      }
    }
  }

  private static class BinaryConverter implements Converter {

    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        BytesColumnVector v = (BytesColumnVector) vector;
        writer.write(column, v.vector[row], v.start[row], v.length[row]);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element, ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        final BytesColumnVector v = (BytesColumnVector) vector;
        writer.write(element, v.vector[row], v.start[row], v.length[row]);
      }
    }
  }

  private static class Decimal18Converter implements Converter {
    final int precision;
    final int scale;

    Decimal18Converter(int precision, int scale) {
      this.precision = precision;
      this.scale = scale;
    }

    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector,
                        int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        HiveDecimalWritable v = ((DecimalColumnVector) vector).vector[row];
        writer.write(column,
            new Decimal().set(v.serialize64(v.scale()), v.precision(), v.scale()),
            precision, scale);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        HiveDecimalWritable v = ((DecimalColumnVector) vector).vector[row];
        writer.write(element,
            new Decimal().set(v.serialize64(v.scale()), v.precision(), v.scale()),
            precision, scale);
      }
    }
  }

  private static class Decimal38Converter implements Converter {
    final int precision;
    final int scale;

    Decimal38Converter(int precision, int scale) {
      this.precision = precision;
      this.scale = scale;
    }

    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector,
                        int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        BigDecimal v = ((DecimalColumnVector) vector).vector[row]
            .getHiveDecimal().bigDecimalValue();
        writer.write(column,
            new Decimal().set(new scala.math.BigDecimal(v), precision, scale),
            precision, scale);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        BigDecimal v = ((DecimalColumnVector) vector).vector[row]
            .getHiveDecimal().bigDecimalValue();
        writer.write(element,
            new Decimal().set(new scala.math.BigDecimal(v), precision, scale),
            precision, scale);
      }
    }
  }

  private static class StructConverter implements Converter {
    private final Converter[] children;
    private final UnsafeRowWriter childWriter;

    StructConverter(final UnsafeWriter parentWriter, final TypeDescription schema) {
      children = new Converter[schema.getChildren().size()];
      for(int c=0; c < children.length; ++c) {
        children[c] = buildConverter(parentWriter, schema.getChildren().get(c));
      }
      childWriter = new UnsafeRowWriter(parentWriter, children.length);
    }

    int writeStruct(StructColumnVector vector, int row) {
      int start = childWriter.cursor();
      childWriter.resetRowWriter();
      for(int c=0; c < children.length; ++c) {
        children[c].convert(childWriter, c, vector.fields[c], row);
      }
      return start;
    }

    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        int start = writeStruct((StructColumnVector) vector, row);
        writer.setOffsetAndSizeFromPreviousCursor(column, start);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        int start = writeStruct((StructColumnVector) vector, row);
        writer.setOffsetAndSizeFromPreviousCursor(element, start);
      }
    }
  }

  private static class ListConverter implements Converter {
    private final Converter children;
    private final UnsafeArrayWriter childWriter;

    ListConverter(final UnsafeWriter parentWriter, TypeDescription schema) {
      TypeDescription child = schema.getChildren().get(0);
      children = buildConverter(parentWriter, child);
      childWriter = new UnsafeArrayWriter(parentWriter, getArrayElementSize(child));

    }

    int writeList(ListColumnVector v, int row) {
      int offset = (int) v.offsets[row];
      int length = (int) v.lengths[row];
      int start = childWriter.cursor();
      childWriter.initialize(length);
      for(int c = 0; c < length; ++c) {
        children.convert(childWriter, c, v.child, offset + c);
      }
      return start;
    }

    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector,
                        int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        int start = writeList((ListColumnVector) vector, row);
        writer.setOffsetAndSizeFromPreviousCursor(column, start);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element,
                        ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        int start = writeList((ListColumnVector) vector, row);
        writer.setOffsetAndSizeFromPreviousCursor(element, start);
      }
    }
  }

  private static class MapConverter implements Converter {
    private final Converter keyConvert;
    private final Converter valueConvert;

    private final UnsafeArrayWriter keyWriter;
    private final UnsafeArrayWriter valueWriter;

    private final int keySize;
    private final int valueSize;

    private final int KEY_SIZE_BYTES = 8;

    MapConverter(final UnsafeWriter parentWriter, TypeDescription schema) {
      final TypeDescription keyType = schema.getChildren().get(0);
      final TypeDescription valueType = schema.getChildren().get(1);
      keyConvert = buildConverter(parentWriter, keyType);
      keySize = getArrayElementSize(keyType);
      keyWriter = new UnsafeArrayWriter(parentWriter, keySize);
      valueConvert = buildConverter(parentWriter, valueType);
      valueSize = getArrayElementSize(valueType);
      valueWriter = new UnsafeArrayWriter(parentWriter, valueSize);
    }

    int writeMap(MapColumnVector v, int row) {
      final int offset = (int) v.offsets[row];
      final int length = (int) v.lengths[row];
      final int start = keyWriter.cursor();

      // save room for the key size
      keyWriter.grow(KEY_SIZE_BYTES);
      keyWriter.increaseCursor(KEY_SIZE_BYTES);

      // serialize the keys
      keyWriter.initialize(length);
      for(int c = 0; c < length; ++c) {
        keyConvert.convert(keyWriter, c, v.keys, offset + c);
      }
      // store the serialized size of the keys
      Platform.putLong(keyWriter.getBuffer(), start,
                keyWriter.cursor() - start - KEY_SIZE_BYTES);

      // serialize the values
      valueWriter.initialize(length);
      for(int c = 0; c < length; ++c) {
        valueConvert.convert(valueWriter, c, v.values, offset + c);
      }
      return start;
    }

    @Override
    public void convert(UnsafeRowWriter writer, int column, ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNullAt(column);
      } else {
        int start = writeMap((MapColumnVector) vector, row);
        writer.setOffsetAndSizeFromPreviousCursor(column, start);
      }
    }

    @Override
    public void convert(UnsafeArrayWriter writer, int element, ColumnVector vector, int row) {
      if (vector.isRepeating) {
        row = 0;
      }
      if (!vector.noNulls && vector.isNull[row]) {
        writer.setNull(element);
      } else {
        int start = writeMap((MapColumnVector) vector, row);
        writer.setOffsetAndSizeFromPreviousCursor(element, start);
      }
    }
  }

  static Converter buildConverter(final UnsafeWriter writer, final TypeDescription schema) {
    switch (schema.getCategory()) {
      case BOOLEAN:
        return new BooleanConverter();
      case BYTE:
        return new ByteConverter();
      case SHORT:
        return new ShortConverter();
      case DATE:
      case INT:
        return new IntConverter();
      case LONG:
        return new LongConverter();
      case FLOAT:
        return new FloatConverter();
      case DOUBLE:
        return new DoubleConverter();
      case TIMESTAMP:
        return new TimestampConverter();
      case DECIMAL:
        if (schema.getPrecision() <= Decimal.MAX_LONG_DIGITS()) {
          return new Decimal18Converter(schema.getPrecision(), schema.getScale());
        } else {
          return new Decimal38Converter(schema.getPrecision(), schema.getScale());
        }
      case BINARY:
      case STRING:
      case CHAR:
      case VARCHAR:
        return new BinaryConverter();
      case STRUCT:
        return new StructConverter(writer, schema);
      case LIST:
        return new ListConverter(writer, schema);
      case MAP:
        return new MapConverter(writer, schema);
      default:
        throw new IllegalArgumentException("Unhandled type " + schema);
    }
  }
}
