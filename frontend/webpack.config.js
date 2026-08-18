const path = require('path')
const { VueLoaderPlugin } = require('vue-loader')
const HtmlWebpackPlugin = require('html-webpack-plugin')

module.exports = {
  entry: './src/main.js',
  output: { path: path.resolve(__dirname, 'dist'), filename: 'assets/[name].[contenthash].js', clean: true, publicPath: '/' },
  resolve: { extensions: ['.js', '.vue', '.json'], alias: { vue$: 'vue/dist/vue.esm.js' } },
  module: { rules: [
    { test: /.vue$/, loader: 'vue-loader' },
    { test: /.js$/, exclude: /node_modules/, use: { loader: 'babel-loader', options: { presets: ['@babel/preset-env'] } } },
    { test: /.css$/, use: ['style-loader', 'css-loader'] }
  ]},
  plugins: [new VueLoaderPlugin(), new HtmlWebpackPlugin({ template: './public/index.html' })],
  devServer: { port: 8081, allowedHosts: 'all', historyApiFallback: true, client: { webSocketURL: { pathname: '/_webpack_hmr' } }, webSocketServer: { type: 'ws', options: { path: '/_webpack_hmr' } }, proxy: [
    { context: ['/api'], target: 'http://127.0.0.1:8090', changeOrigin: true, ws: false, timeout: 0, proxyTimeout: 0 }
  ] }
}
