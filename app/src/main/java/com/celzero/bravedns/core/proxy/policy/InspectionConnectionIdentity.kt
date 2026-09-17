package com.celzero.bravedns.core.proxy.policy

data class InspectionConnectionIdentity(
  val uid: Int?,
  val packageNames: Set<String>,
)
