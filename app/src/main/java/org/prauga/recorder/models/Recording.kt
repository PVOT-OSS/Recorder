/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.models

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import java.util.Date
import org.prauga.recorder.ext.readParcelable

data class Recording(
    val uri: Uri,
    val title: String,
    val dateAdded: Date,
    val duration: Long,
    val id: Long = 0,
    val mimeType: String = "",
    val size: Long = 0,
    val favourite: Boolean = false,
    val labels: List<String> = emptyList(),
    val locationName: String? = null,
    /** While searching: the transcript excerpt that matched. */
    val snippet: String? = null,
) : Parcelable {
    constructor(
        parcel: Parcel
    ) : this(
        parcel.readParcelable(Uri::class)!!,
        parcel.readString()!!,
        Date(parcel.readLong()),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readString()!!,
        parcel.readLong(),
        parcel.readInt() != 0,
        parcel.createStringArrayList()!!,
        parcel.readString(),
        parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(uri, flags)
        parcel.writeString(title)
        parcel.writeLong(dateAdded.time)
        parcel.writeLong(duration)
        parcel.writeLong(id)
        parcel.writeString(mimeType)
        parcel.writeLong(size)
        parcel.writeInt(if (favourite) 1 else 0)
        parcel.writeStringList(labels)
        parcel.writeString(locationName)
        parcel.writeString(snippet)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<Recording> {
        override fun createFromParcel(parcel: Parcel) = Recording(parcel)

        override fun newArray(size: Int) = arrayOfNulls<Recording>(size)

        fun fromMediaStore(
            uri: Uri,
            title: String,
            dateAdded: Long,
            duration: Long,
            id: Long = 0,
            mimeType: String = "",
            size: Long = 0,
        ) = Recording(uri, title, Date(dateAdded * 1000), duration, id, mimeType, size)
    }
}
