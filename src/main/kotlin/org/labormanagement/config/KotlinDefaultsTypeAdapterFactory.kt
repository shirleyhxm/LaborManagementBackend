package org.labormanagement.config

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

/**
 * Gson deserializes via reflection and has no notion of Kotlin default
 * parameter values: a JSON object that omits a field results in Gson
 * passing null for it, which throws NullPointerException for any
 * non-nullable Kotlin property with a default (e.g. `val groups: Set<String>
 * = emptySet()`), since the Kotlin-generated null-check runs regardless of
 * how the object was constructed.
 *
 * This factory intercepts deserialization for Kotlin data classes and calls
 * their primary constructor via reflection with KFunction.callBy(), which -
 * unlike Gson's own construction path - correctly applies Kotlin default
 * values for any constructor parameter not explicitly supplied in the JSON.
 */
class KotlinDefaultsTypeAdapterFactory : TypeAdapterFactory {

    override fun <T : Any> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val rawType = type.rawType
        val kClass = rawType.kotlin
        val constructor = kClass.primaryConstructor ?: return null

        // Only intervene for classes with at least one optional constructor
        // parameter; everything else can use Gson's default handling.
        if (constructor.parameters.none { it.isOptional }) return null

        val delegate = gson.getDelegateAdapter(this, type)
        val elementAdapter = gson.getAdapter(com.google.gson.JsonElement::class.java)

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) {
                delegate.write(out, value)
            }

            override fun read(reader: JsonReader): T? {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    return null
                }

                val jsonElement = elementAdapter.read(reader)
                if (jsonElement == null || !jsonElement.isJsonObject) {
                    // Not an object (or null) - fall back to the delegate,
                    // which will produce Gson's normal error/behavior.
                    return delegate.fromJsonTree(jsonElement)
                }
                val jsonObject = jsonElement.asJsonObject

                val args = mutableMapOf<KParameter, Any?>()
                for (param in constructor.parameters) {
                    val name = param.name ?: continue
                    if (!jsonObject.has(name)) {
                        // Field omitted entirely: let callBy() apply the
                        // Kotlin default rather than passing null.
                        continue
                    }

                    val element = jsonObject.get(name)
                    if (element != null && element.isJsonNull && !param.type.isMarkedNullable) {
                        // Field explicitly null but the Kotlin type is
                        // non-nullable: still prefer the default over null.
                        continue
                    }

                    val paramType = TypeToken.get(param.type.javaType)
                    val paramAdapter = gson.getAdapter(paramType)
                    args[param] = paramAdapter.fromJsonTree(element)
                }

                @Suppress("UNCHECKED_CAST")
                return constructor.callBy(args) as T
            }
        }
    }
}
