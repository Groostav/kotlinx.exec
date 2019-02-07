package groostav.kotlinx.exec

import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.junit.Test


open class AspectHelpers {

    @lazy_list open fun thingy(): List<Int> {
        return listOf(1, 2, 3)
    }

    @Test fun `stuff`(): Unit{
        val x = MyAspect();
        val result= thingy()

        TODO();
    }
}

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class lazy_list

@Aspect
class MyAspect {

    @Pointcut("execution(@annotation.lazy_list * *(..))")
    fun doPOintCut(){
        println("yarr!! we're on the class path me harties!!")
    }

}