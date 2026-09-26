package io.github.smallmiro.teslable

/** 라이브러리 내부 모듈 간 공유용. 앱 코드에서 쓰면 호환성을 보장하지 않는다. */
@RequiresOptIn(
    message = "Internal teslable API. Not stable for application use.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class InternalTeslableApi
