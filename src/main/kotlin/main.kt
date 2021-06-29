import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.Type.MESSAGE
import com.google.protobuf.Message
import de.m3y.kformat.Table
import de.m3y.kformat.table
import java.lang.StringBuilder

fun main() {
  val docR1 = document {
    docId = 10
    links = links {
      forward += listOf(20, 40, 60)
    }
    name += name {
      language += language {
        code = "en-us"
        country = "us"
      }
      language += language {
        code = "en"
      }
      url = "http://A"
    }
    name += name {
      url = "http://B"
    }
    name += name {
      language += language {
        code = "en-gb"
        country = "gb"
      }
    }
  }
  val docR2 = document {
    docId = 20
    links = links {
      backward += listOf(10, 30)
      forward += 80
    }
    name += name {
      url = "http://C"
    }
  }

  prettyPrint(Splitter(docR1).split())
  println("~".repeat(20))
  prettyPrint(Splitter(docR2).split())
}

private fun prettyPrint(splitR1: Map<String, MutableList<ColumnRecord>>) {
  val sb = StringBuilder()
  for ((columnName, records) in splitR1.entries.sortedBy { it.key }) {
    sb.appendLine(columnName)
    table {
      header("value", "r", "d")
      records.forEach { row(it.value.toString(), it.repetitionLevel, it.definitionLevel) }
      hints {
        borderStyle = Table.BorderStyle.SINGLE_LINE
      }
    }.render(sb)
    sb.appendLine()
  }

  print(sb.toString())
}

data class ColumnRecord(val value: Any?, val repetitionLevel: Int, val definitionLevel: Int)

/**
 * Implementation of a column striping algorithm described in
 * Melnik, Sergey, et al. "Dremel: interactive analysis of web-scale datasets."
 * Proceedings of the VLDB Endowment 3.1-2 (2010): 330-339.
 */
class Splitter(val message: Message) {
  private val columns: MutableMap<String, MutableList<ColumnRecord>> = HashMap()

  fun split(): Map<String, MutableList<ColumnRecord>> {
    if (columns.isEmpty())
      traverse(message, 0, 0, 0)

    return columns
  }

  /**
   * Recursively traverse a protobuf message converting it to a column-striped representation.
   *
   * Protobuf message can be seen as a tree, where nodes are fields and links defined by
   * child-parent relation. Fields can be optional and repeated. This function recursively
   * traverses a protobuf schema and checks the presence of each field in the message.
   * While function traverses the tree it counts the number of repeatable fields on a path
   * from the root to a current point and keeps track of which field (on the path) was repeated
   * last. It also counts the number of fields on a path which are non required (i.e. optional
   * or repeated) but were initialised.
   *
   * @param msg - protobuf message to convert
   * @param prevRepetitionLevel - which field on a path was repeated last
   * @param prevDefinitionLevel - number of non-required but present fields
   * @param numberOfRepeatableParents - number of repeatable fields on a path from the root to
   * a current point
   */
  private fun traverse(
    msg: Message,
    prevRepetitionLevel: Int,
    prevDefinitionLevel: Int,
    numberOfRepeatableParents: Int,
  ) {
    for (field in msg.descriptorForType.fields) {
      if (field.isRepeated) {
        val values = msg.getField(field) as Collection<*>
        if (values.isEmpty()) {
          markAbsentSubtree(
            field,
            prevRepetitionLevel,
            prevDefinitionLevel,
          )
          continue
        }

        val definitionLevel = prevDefinitionLevel + 1
        for ((i, value) in values.withIndex()) {
          val repetitionLevel =
            if (i == 0) prevRepetitionLevel else numberOfRepeatableParents + 1

          if (field.type == MESSAGE) {
            traverse(
              value as Message,
              repetitionLevel,
              definitionLevel,
              numberOfRepeatableParents + 1,
            )
          } else {
            addColumnRecord(
              field,
              value,
              repetitionLevel,
              definitionLevel,
            )
          }
        }
      } else {
        val value = msg.getField(field)
        val definitionLevel =
          if (field.isOptional && msg.hasField(field)) prevDefinitionLevel + 1 else prevDefinitionLevel

        if (field.type == MESSAGE) {
          if (msg.hasField(field)) {
            traverse(
              value as Message,
              prevRepetitionLevel,
              definitionLevel,
              numberOfRepeatableParents,
            )
          } else {
            markAbsentSubtree(
              field,
              prevRepetitionLevel,
              definitionLevel,
            )
          }
        } else {
          val valueOrNull = if (msg.hasField(field)) value else null
          addColumnRecord(
            field,
            valueOrNull,
            prevRepetitionLevel,
            definitionLevel,
          )
        }
      }
    }
  }

  private fun addColumnRecord(
    field: FieldDescriptor,
    v: Any?,
    repetitionLevel: Int,
    definitionLevel: Int,
  ) {
    columns.computeIfAbsent(field.fullName) { ArrayList() }
      .add(ColumnRecord(v, repetitionLevel, definitionLevel))
  }

  /**
   * Mark the field and all descendants as absent
   */
  private fun markAbsentSubtree(
    field: FieldDescriptor,
    repetitionLevel: Int,
    definitionLevel: Int,
  ) {
    if (field.type == MESSAGE) {
      for (child in field.messageType.fields) {
        markAbsentSubtree(child, repetitionLevel, definitionLevel)
      }
    } else {
      addColumnRecord(
        field, null, repetitionLevel, definitionLevel
      )
    }
  }
}