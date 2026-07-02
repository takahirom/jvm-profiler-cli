package io.github.takahirom.clijvm

import com.github.ajalt.clikt.core.subcommands
import io.github.takahirom.clijvm.cli.ClijvmCommand
import io.github.takahirom.clijvm.cli.CpuCommand
import io.github.takahirom.clijvm.cli.CpuStartCommand
import io.github.takahirom.clijvm.cli.CpuStatusCommand
import io.github.takahirom.clijvm.cli.CpuStopCommand
import io.github.takahirom.clijvm.cli.HeapCommand
import io.github.takahirom.clijvm.cli.ListCommand
import io.github.takahirom.clijvm.cli.MemoryCommand
import io.github.takahirom.clijvm.cli.MemoryStartCommand
import io.github.takahirom.clijvm.cli.MemoryStatusCommand
import io.github.takahirom.clijvm.cli.MemoryStopCommand
import io.github.takahirom.clijvm.cli.ReportCommand

fun main(args: Array<String>) {
    val cpu = CpuCommand().subcommands(
        CpuStartCommand(),
        CpuStopCommand(),
        CpuStatusCommand(),
    )
    val memory = MemoryCommand().subcommands(
        MemoryStartCommand(),
        MemoryStopCommand(),
        MemoryStatusCommand(),
    )
    ClijvmCommand()
        .subcommands(ListCommand(), cpu, memory, HeapCommand(), ReportCommand())
        .main(args)
}
