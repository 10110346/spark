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

package org.apache.spark.sql.catalyst.csv

import java.nio.charset.StandardCharsets
import java.util.{Locale, TimeZone}

import com.univocity.parsers.csv.{CsvParserSettings, CsvWriterSettings, UnescapedQuoteHandling}
import org.apache.commons.lang3.time.FastDateFormat

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.internal.SQLConf

class CSVOptions(
    @transient val parameters: CaseInsensitiveMap[String],
    val columnPruning: Boolean,
    defaultTimeZoneId: String,
    defaultColumnNameOfCorruptRecord: String)
  extends Logging with Serializable {

  def this(
    parameters: Map[String, String],
    columnPruning: Boolean,
    defaultTimeZoneId: String) = {
    this(
      CaseInsensitiveMap(parameters),
      columnPruning,
      defaultTimeZoneId,
      SQLConf.get.columnNameOfCorruptRecord)
  }

  def this(
    parameters: Map[String, String],
    columnPruning: Boolean,
    defaultTimeZoneId: String,
    defaultColumnNameOfCorruptRecord: String) = {
      this(
        CaseInsensitiveMap(parameters),
        columnPruning,
        defaultTimeZoneId,
        defaultColumnNameOfCorruptRecord)
  }

  private def getChar(paramName: String, default: Char): Char = {
    val paramValue = parameters.get(paramName)
    paramValue match {
      case None => default
      case Some(null) => default
      case Some(value) if value.length == 0 => '\u0000'
      case Some(value) if value.length == 1 => value.charAt(0)
      case _ => throw new RuntimeException(s"$paramName cannot be more than one character")
    }
  }

  private def getInt(paramName: String, default: Int): Int = {
    val paramValue = parameters.get(paramName)
    paramValue match {
      case None => default
      case Some(null) => default
      case Some(value) => try {
        value.toInt
      } catch {
        case e: NumberFormatException =>
          throw new RuntimeException(s"$paramName should be an integer. Found $value")
      }
    }
  }

  private def getBool(paramName: String, default: Boolean = false): Boolean = {
    val param = parameters.getOrElse(paramName, default.toString)
    if (param == null) {
      default
    } else if (param.toLowerCase(Locale.ROOT) == "true") {
      true
    } else if (param.toLowerCase(Locale.ROOT) == "false") {
      false
    } else {
      throw new Exception(s"$paramName flag can be true or false")
    }
  }

  val delimiter = CSVExprUtils.toChar(
    parameters.getOrElse("sep", parameters.getOrElse("delimiter", ",")))
  val parseMode: ParseMode =
    parameters.get("mode").map(ParseMode.fromString).getOrElse(PermissiveMode)
  val charset = parameters.getOrElse("encoding",
    parameters.getOrElse("charset", StandardCharsets.UTF_8.name()))

  val quote = getChar("quote", '\"')
  val escape = getChar("escape", '\\')
  val charToEscapeQuoteEscaping = parameters.get("charToEscapeQuoteEscaping") match {
    case None => None
    case Some(null) => None
    case Some(value) if value.length == 0 => None
    case Some(value) if value.length == 1 => Some(value.charAt(0))
    case _ =>
      throw new RuntimeException("charToEscapeQuoteEscaping cannot be more than one character")
  }
  val comment = getChar("comment", '\u0000')

  val headerFlag = getBool("header")
  val inferSchemaFlag = getBool("inferSchema")
  val ignoreLeadingWhiteSpaceInRead = getBool("ignoreLeadingWhiteSpace", default = false)
  val ignoreTrailingWhiteSpaceInRead = getBool("ignoreTrailingWhiteSpace", default = false)

  // For write, both options were `true` by default. We leave it as `true` for
  // backwards compatibility.
  val ignoreLeadingWhiteSpaceFlagInWrite = getBool("ignoreLeadingWhiteSpace", default = true)
  val ignoreTrailingWhiteSpaceFlagInWrite = getBool("ignoreTrailingWhiteSpace", default = true)

  val columnNameOfCorruptRecord =
    parameters.getOrElse("columnNameOfCorruptRecord", defaultColumnNameOfCorruptRecord)

  val nullValue = parameters.getOrElse("nullValue", "")

  val nanValue = parameters.getOrElse("nanValue", "NaN")

  val positiveInf = parameters.getOrElse("positiveInf", "Inf")
  val negativeInf = parameters.getOrElse("negativeInf", "-Inf")


  val compressionCodec: Option[String] = {
    val name = parameters.get("compression").orElse(parameters.get("codec"))
    name.map(CompressionCodecs.getCodecClassName)
  }

  val timeZone: TimeZone = DateTimeUtils.getTimeZone(
    parameters.getOrElse(DateTimeUtils.TIMEZONE_OPTION, defaultTimeZoneId))

  // A language tag in IETF BCP 47 format
  val locale: Locale = parameters.get("locale").map(Locale.forLanguageTag).getOrElse(Locale.US)

  // Uses `FastDateFormat` which can be direct replacement for `SimpleDateFormat` and thread-safe.
  val dateFormat: FastDateFormat =
    FastDateFormat.getInstance(parameters.getOrElse("dateFormat", "yyyy-MM-dd"), locale)

  val timestampFormat: FastDateFormat =
    FastDateFormat.getInstance(
      parameters.getOrElse("timestampFormat", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"), timeZone, locale)

  val multiLine = parameters.get("multiLine").map(_.toBoolean).getOrElse(false)

  val maxColumns = getInt("maxColumns", 20480)

  val maxCharsPerColumn = getInt("maxCharsPerColumn", -1)

  val escapeQuotes = getBool("escapeQuotes", true)

  val quoteAll = getBool("quoteAll", false)

  val inputBufferSize = 128

  val isCommentSet = this.comment != '\u0000'

  val samplingRatio =
    parameters.get("samplingRatio").map(_.toDouble).getOrElse(1.0)

  /**
   * Forcibly apply the specified or inferred schema to datasource files.
   * If the option is enabled, headers of CSV files will be ignored.
   */
  val enforceSchema = getBool("enforceSchema", default = true)


  /**
   * String representation of an empty value in read and in write.
   */
  val emptyValue = parameters.get("emptyValue")
  /**
   * The string is returned when CSV reader doesn't have any characters for input value,
   * or an empty quoted string `""`. Default value is empty string.
   */
  val emptyValueInRead = emptyValue.getOrElse("")
  /**
   * The value is used instead of an empty string in write. Default value is `""`
   */
  val emptyValueInWrite = emptyValue.getOrElse("\"\"")
  /**
   * This option only effects on quotes which are on the begin and end.
   */
  val keepQuotes = getBool("keepQuotes", false)

  /**
   * A string between two consecutive JSON records.
   */
  val lineSeparator: Option[String] = parameters.get("lineSep").map { sep =>
    require(sep.nonEmpty, "'lineSep' cannot be an empty string.")
    require(sep.length == 1, "'lineSep' can contain only 1 character.")
    sep
  }

  val lineSeparatorInRead: Option[Array[Byte]] = lineSeparator.map { lineSep =>
    lineSep.getBytes(charset)
  }
  val lineSeparatorInWrite: Option[String] = lineSeparator

  def asWriterSettings: CsvWriterSettings = {
    val writerSettings = new CsvWriterSettings()
    val format = writerSettings.getFormat
    format.setDelimiter(delimiter)
    format.setQuote(quote)
    format.setQuoteEscape(escape)
    charToEscapeQuoteEscaping.foreach(format.setCharToEscapeQuoteEscaping)
    format.setComment(comment)
    lineSeparatorInWrite.foreach(format.setLineSeparator)

    writerSettings.setIgnoreLeadingWhitespaces(ignoreLeadingWhiteSpaceFlagInWrite)
    writerSettings.setIgnoreTrailingWhitespaces(ignoreTrailingWhiteSpaceFlagInWrite)
    writerSettings.setNullValue(nullValue)
    writerSettings.setEmptyValue(emptyValueInWrite)
    writerSettings.setSkipEmptyLines(true)
    writerSettings.setQuoteAllFields(quoteAll)
    writerSettings.setQuoteEscapingEnabled(escapeQuotes)
    writerSettings
  }

  def asParserSettings: CsvParserSettings = {
    val settings = new CsvParserSettings()
    val format = settings.getFormat
    format.setDelimiter(delimiter)
    format.setQuote(quote)
    format.setQuoteEscape(escape)
    lineSeparator.foreach(format.setLineSeparator)
    charToEscapeQuoteEscaping.foreach(format.setCharToEscapeQuoteEscaping)
    format.setComment(comment)

    settings.setIgnoreLeadingWhitespaces(ignoreLeadingWhiteSpaceInRead)
    settings.setIgnoreTrailingWhitespaces(ignoreTrailingWhiteSpaceInRead)
    settings.setReadInputOnSeparateThread(false)
    settings.setInputBufferSize(inputBufferSize)
    settings.setMaxColumns(maxColumns)
    settings.setNullValue(nullValue)
    settings.setEmptyValue(emptyValueInRead)
    settings.setMaxCharsPerColumn(maxCharsPerColumn)
    settings.setUnescapedQuoteHandling(UnescapedQuoteHandling.STOP_AT_DELIMITER)
    settings.setLineSeparatorDetectionEnabled(lineSeparatorInRead.isEmpty && multiLine)
    lineSeparatorInRead.foreach { _ =>
      settings.setNormalizeLineEndingsWithinQuotes(!multiLine)
    }
    settings.setKeepQuotes(keepQuotes)

    settings
  }
}
