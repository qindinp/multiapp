package com.multiapp.core.hook

class QqReaderCompatProfile : AppCompatProfile {

    override val packageName: String = "com.qq.reader"

    override val knownPacker: PackerType = PackerType.JIAGU_360

    override val startupNeutralizeList: List<String> = listOf(
        "com.qq.reader.cservice.onlineread.qdae.search",
        "com.yuewen.component.businesstask.ordinal.ReaderProtocolJSONTask.onFinish",
        "com.yuewen.reader.framework.utils.qdag.judian",
        "com.qq.reader.ywreader.component.compatible.qdaf.getOnlineChapterFilePath",
        "com.qq.reader.ywreader.component.compatible.qdaf.search"
    )

    override val forbiddenNeutralizeList: List<String> = listOf(
        "com.qq.reader.app.QQReaderApplication.onCreate",
        "com.qq.reader.login.AccountManager.init",
        "com.yuewen.reader.framework.bookshelf.BookShelfManager.init"
    )

    override val diagnosticHooks: List<String> = listOf(
        "QqReaderEqctPlaintextCompat",
        "QqReaderFileJavaDiag",
        "QqReaderProtocolDiag",
        "QqReaderProviderDiag"
    )
}
